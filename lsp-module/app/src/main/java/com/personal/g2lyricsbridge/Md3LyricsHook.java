package com.personal.g2lyricsbridge;

import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Bridges MD3Music's live lyrics to the Even G2 plugin over 127.0.0.1:21771.
 *
 * <p>Hook strategies, in fallback order:
 *
 * <ul>
 *   <li><b>Fast dispatcher</b>: the 6.3.0 release merged every channel handler into the
 *       obfuscated {@code androidx.lifecycle.m} kitchen-sink class. Tried first because it
 *       receives Dart's raw {@code Map} before any Kotlin conversion.</li>
 *   <li><b>Named builder</b>: {@code AudioPlaybackService$Companion} exists only in debug
 *       builds; hooked when present.</li>
 *   <li><b>Generic structural discovery</b>: release builds rename classes and methods
 *       between versions (6.3.0 standard vs 6.4.1 随身听兼容包), so after the service /
 *       activity comes up we enumerate every class in the app dex and match shapes that
 *       cannot be renamed by R8:
 *     <ul>
 *       <li>channel dispatcher: {@code void (call, result)} where {@code result} exposes
 *           the Flutter {@code Result} shape ({@code success(Object)} +
 *           {@code error(String,String,Object)});</li>
 *       <li>song builder: a two-argument method taking {@code Map} + {@code String} in
 *           either order and returning a non-void Song object;</li>
 *       <li>playback fallback: {@code (long, boolean) -> PlaybackState}.</li>
 *     </ul>
 *     Instance-held collaborator classes are walked by reflection as a second source.
 *   </li>
 * </ul>
 *
 * <p>Playback state always comes from the framework {@code MediaSession#setPlaybackState},
 * whose name can never be renamed by R8.
 *
 * <p>All traffic stays on the loopback interface: the server binds
 * {@link InetAddress#getLoopbackAddress()} so nothing is reachable from the LAN.
 */
public final class Md3LyricsHook implements IXposedHookLoadPackage {
    private static final String TAG = "G2LyricsBridge";

    /**
     * MD3Music ships two applicationIds from the same source tree (see the CI matrix):
     * the 精简版 keeps {@code com.md3music.md3music}, the 随身听兼容版 overrides
     * {@code distribution} to {@code com.apple.android.music}. Both must be supported, or
     * the module silently does nothing for half the installs.
     */
    private static final Set<String> TARGET_PACKAGES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("com.md3music.md3music", "com.apple.android.music")));

    /** Fast path: present in the 6.3.0 release; absent (renamed) in 6.4.1. */
    private static final String[] DISPATCHER_CANDIDATES = {"androidx.lifecycle.m"};

    /** Classes already hooked, so repeated discovery passes do not double-inject. */
    private final Set<String> hookedClasses = Collections.synchronizedSet(new HashSet<String>());

    private ClassLoader appClassLoader;

    private static volatile boolean booted;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGES.contains(lpparam.packageName)) return;
        synchronized (Md3LyricsHook.class) {
            if (booted) return;
            booted = true;
        }

        LyricsStore store = new LyricsStore(lpparam.packageName);
        this.appClassLoader = lpparam.classLoader;
        LocalLyricsServer server = new LocalLyricsServer(store);
        server.start();

        hookPlaybackState(lpparam, store);
        hookLyricSource(lpparam, store);
    }

    // ── playback state ────────────────────────────────────────────────────────────

    private void hookPlaybackState(XC_LoadPackage.LoadPackageParam lpparam, final LyricsStore store) {
        try {
            XposedHelpers.findAndHookMethod("android.media.session.MediaSession", lpparam.classLoader,
                    "setPlaybackState", PlaybackState.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object state = param.args[0];
                            if (state instanceof PlaybackState) {
                                store.setPlayback((PlaybackState) state);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": MediaSession.setPlaybackState hooked");
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": MediaSession hook failed: " + error);
        }
    }

    // ── lyric source ─────────────────────────────────────────────────────────────

    private void hookLyricSource(XC_LoadPackage.LoadPackageParam lpparam, final LyricsStore store) {
        // Strategy 1: known dispatcher class (6.3.0 fast path).
        if (hookDispatcherCandidates(lpparam.classLoader, store)) return;

        // Strategy 2: debug build companion, kept for completeness.
        Class<?> named = XposedHelpers.findClassIfExists("com.md3music.md3music.AudioPlaybackService$Companion", lpparam.classLoader);
        if (named != null && scanClass(named, store, "named")) return;

        // Strategy 3: generic discovery. Trigger it now (background) and again once the
        // service / activity actually exists, because obfuscated classes may load lazily.
        store.setStatus("discovering lyric hooks");
        triggerDiscovery(lpparam.classLoader, store, "initial");

        hookLifecycle(lpparam.classLoader, store, "android.app.Service", "onCreate");
        hookLifecycle(lpparam.classLoader, store, "android.app.Activity", "onCreate");
    }

    /** Hooks framework lifecycle methods that mark a good moment to re-scan classes. */
    private void hookLifecycle(ClassLoader classLoader, final LyricsStore store,
                               String className, String methodName) {
        try {
            XposedHelpers.findAndHookMethod(className, classLoader, methodName,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object instance = param.thisObject;
                            Set<Class<?>> reached = new HashSet<>();
                            collectInstanceClasses(instance, reached, 2);
                            for (Class<?> c : reached) {
                                scanClass(c, store, "instance");
                            }
                            triggerDiscovery(appClassLoader, store, "lifecycle");
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": " + className + "." + methodName + " hook failed: " + error);
        }
    }

    private void triggerDiscovery(final ClassLoader classLoader, final LyricsStore store, String why) {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                scanAllClasses(classLoader, store, "dex");
            }
        }, "G2LyricsBridge-discovery-" + why);
        thread.setDaemon(true);
        thread.start();
    }

    // ── class discovery ───────────────────────────────────────────────────────────

    /** Enumerates every class listed in the app's dex files and scans each structurally. */
    private void scanAllClasses(ClassLoader classLoader, LyricsStore store, String how) {
        List<Class<?>> classes = enumerateClasses(classLoader);
        int matched = 0;
        for (Class<?> c : classes) {
            try {
                if (scanClass(c, store, how)) matched++;
            } catch (Throwable error) {
                // One unloadable class must not abort the whole pass.
            }
        }
        if (!store.lyricHookInstalled()) {
            store.setStatus("scanned " + classes.size() + " classes, no lyric hook match");
            XposedBridge.log(TAG + ": dex scan over " + classes.size() + " classes; matched " + matched);
        }
    }

    /** Reflectively lists all class names known to the loader's dex elements. */
    private static List<Class<?>> enumerateClasses(ClassLoader classLoader) {
        List<Class<?>> out = new ArrayList<>();
        try {
            Object pathList = readField(classLoader, "pathList");
            if (pathList == null) return out;
            Object elements = readField(pathList, "dexElements");
            if (!(elements instanceof Object[])) return out;
            for (Object element : (Object[]) elements) {
                Object dexFile = readField(element, "dexFile");
                if (dexFile == null) continue;
                Enumeration<?> entries;
                try {
                    Method entriesMethod = dexFile.getClass().getMethod("entries");
                    entries = (Enumeration<?>) entriesMethod.invoke(dexFile);
                } catch (Throwable ignored) {
                    continue;
                }
                while (entries.hasMoreElements()) {
                    String name = (String) entries.nextElement();
                    try {
                        out.add(Class.forName(name, false, classLoader));
                    } catch (Throwable ignored) {
                        // Class references a missing type; skip it.
                    }
                }
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": class enumeration failed: " + error);
        }
        return out;
    }

    /** Walks an object's field graph (limited depth) and collects the referenced classes. */
    private void collectInstanceClasses(Object root, Set<Class<?>> out, int depth) {
        if (root == null || depth < 0) return;
        Class<?> c = root.getClass();
        if (!out.add(c)) return;
        if (depth == 0) return;
        Class<?> scan = c;
        while (scan != null && scan != Object.class) {
            Field[] fields;
            try {
                fields = scan.getDeclaredFields();
            } catch (Throwable ignored) {
                break;
            }
            for (Field field : fields) {
                if (field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(root);
                    if (value != null) collectInstanceClasses(value, out, depth - 1);
                } catch (Throwable ignored) {
                    // Inaccessible field only costs one lookup.
                }
            }
            scan = scan.getSuperclass();
        }
    }

    // ── structural scan ───────────────────────────────────────────────────────────

    /**
     * Scans one class for dispatcher / builder / playback-shape methods and hooks matches.
     * Returns true when at least one lyric-related hook was installed here.
     */
    private boolean scanClass(Class<?> holder, LyricsStore store, String how) {
        if (holder == null) return false;
        String key = holder.getName();
        synchronized (hookedClasses) {
            if (hookedClasses.contains(key)) return false;
        }

        Method[] methods;
        try {
            methods = holder.getDeclaredMethods();
        } catch (Throwable error) {
            return false;
        }

        boolean installed = false;
        for (Method method : methods) {
            try {
                Class<?>[] params = method.getParameterTypes();

                // Dispatcher: void (call, result) with a Result-shaped second argument.
                if (method.getReturnType() == Void.TYPE && params.length == 2
                        && looksLikeResult(params[1])) {
                    XposedBridge.hookMethod(method, new ChannelDispatcherHook(
                            params[0], holder.getName(), method.getName(), store));
                    markHook(holder, how, "dispatcher", store);
                    installed = true;
                    continue;
                }

                // Builder: (Map, String) or (String, Map) -> non-void Song.
                if (params.length == 2 && method.getReturnType() != Void.TYPE && builderShape(params)) {
                    XposedBridge.hookMethod(method, new BuilderHook(store));
                    markHook(holder, how, "song builder", store);
                    installed = true;
                    continue;
                }

                // Playback fallback: (long, boolean) -> PlaybackState.
                if (params.length == 2 && params[0] == Long.TYPE && params[1] == Boolean.TYPE
                        && PlaybackState.class == method.getReturnType()) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            store.setPlaybackArgs((Long) param.args[0], (Boolean) param.args[1]);
                        }
                    });
                    installed = true;
                }
            } catch (Throwable error) {
                XposedBridge.log(TAG + ": scan of " + method + " failed: " + error);
            }
        }

        if (installed) {
            hookedClasses.add(key);
        }
        return installed;
    }

    private static boolean builderShape(Class<?>[] params) {
        boolean map = params[0] == Map.class || params[1] == Map.class;
        boolean hasString = params[0] == String.class || params[1] == String.class;
        return map && hasString;
    }

    private void markHook(Class<?> holder, String how, String kind, LyricsStore store) {
        store.markLyricHook(kind + " " + holder.getName() + " (" + how + ")");
        XposedBridge.log(TAG + ": " + kind + " hooked on " + holder.getName() + " [" + how + "]");
    }

    // ── explicit 6.3.0 dispatcher fast path ───────────────────────────────────────

    private boolean hookDispatcherCandidates(ClassLoader classLoader, LyricsStore store) {
        boolean installed = false;
        for (String name : DISPATCHER_CANDIDATES) {
            Class<?> holder = XposedHelpers.findClassIfExists(name, classLoader);
            if (holder == null) continue;
            if (scanClass(holder, store, "fast")) installed = true;
        }
        return installed;
    }

    /** Flutter's {@code Result}: {@code success(Object)} and {@code error(String,String,Object)}. */
    private static boolean looksLikeResult(Class<?> type) {
        boolean success = false;
        boolean error = false;
        for (Method method : type.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (method.getReturnType() != Void.TYPE) continue;
            if (params.length == 3 && params[0] == String.class && params[1] == String.class) {
                error = true;
            } else if (params.length == 1 && params[0] == Object.class) {
                success = true;
            }
        }
        return success && error;
    }

    /**
     * Reads the {@code MethodCall} lazily by value instead of by field name: the field that
     * holds a {@code String} equal to "setSong" identifies the call, and the field that holds
     * a {@code Map} is the argument bundle. A diagnostic dump of every intercepted call is
     * kept in the store so obfuscated field layouts can be diagnosed over /v1/health.
     */
    private static final class ChannelDispatcherHook extends XC_MethodHook {
        private final Field[] fields;
        private final Method[] stringGetters;
        private final LyricsStore store;
        private final String origin;

        ChannelDispatcherHook(Class<?> callType, String holder, String method, LyricsStore store) {
            this.store = store;
            this.origin = holder + "#" + method;
            List<Field> accessible = new ArrayList<>();
            for (Field field : callType.getDeclaredFields()) {
                if (field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    accessible.add(field);
                } catch (Throwable ignored) {
                    // A field we cannot reach only costs us one lookup.
                }
            }
            this.fields = accessible.toArray(new Field[0]);
            List<Method> getters = new ArrayList<>();
            try {
                for (Method method2 : callType.getDeclaredMethods()) {
                    if (method2.getParameterCount() == 0 && method2.getReturnType() == String.class) {
                        try { method2.setAccessible(true); getters.add(method2); } catch (Throwable ignored) { }
                    }
                }
            } catch (Throwable ignored) { }
            this.stringGetters = getters.toArray(new Method[0]);
        }

        @Override protected void beforeHookedMethod(MethodHookParam param) {
            Object call = param.args[0];
            if (call == null) return;
            boolean isSetSong = false;
            Object bundle = null;
            StringBuilder dump = new StringBuilder(origin).append(" | ");
            for (Field field : fields) {
                try {
                    Object value = field.get(call);
                    dump.append(field.getType().getSimpleName()).append('=').append(shortValue(value)).append("; ");
                    if (value instanceof String && "setSong".equals(value)) {
                        isSetSong = true;
                    } else if (value instanceof Map<?, ?>) {
                        bundle = value;
                    }
                } catch (Throwable ignored) {
                    // Keep scanning: one inaccessible field must not drop the call.
                }
            }
            for (Method getter : stringGetters) {
                try {
                    String value = (String) getter.invoke(call);
                    dump.append("getter:").append(shortValue(value)).append("; ");
                    if ("setSong".equals(value)) isSetSong = true;
                } catch (Throwable ignored) { }
            }
            store.noteCall(dump.toString());
            Log.d(TAG, "channel call " + dump);
            if (!isSetSong) return;

            Object song = null;
            if (bundle instanceof Map<?, ?>) {
                Map<?, ?> args = (Map<?, ?>) bundle;
                song = args.get("song");
                if (!(song instanceof Map<?, ?>) && args.containsKey("lyrics")) song = args;
            }
            if (song instanceof Map<?, ?>) {
                store.setSongMap((Map<?, ?>) song);
                store.noteSetSong();
            }
        }
    }

    private static String shortValue(Object value) {
        if (value == null) return "null";
        String text = String.valueOf(value);
        if (text.length() > 80) text = text.substring(0, 80) + "…";
        return value.getClass().getSimpleName() + "{" + text + "}";
    }

    /**
     * Song builder: captures the raw {@code Map} argument before conversion, and the
     * returned Song object as a reflective fallback for obfuscated builds. The Map always
     * wins: its Dart string keys ({@code name/artist/...}) survive R8, whereas the Song
     * object's obfuscated fields can only be matched by type order, which is unreliable.
     */
    private static final class BuilderHook extends XC_MethodHook {
        private final LyricsStore store;
        private boolean mapSeen;

        BuilderHook(LyricsStore store) {
            this.store = store;
        }

        @Override protected void beforeHookedMethod(MethodHookParam param) {
            StringBuilder dump = new StringBuilder("builder args: ");
            for (Object arg : param.args) {
                dump.append(arg == null ? "null" : arg.getClass().getSimpleName()).append(", ");
                if (arg instanceof Map<?, ?>) {
                    Map<?, ?> map = (Map<?, ?>) arg;
                    dump.append("keys=").append(map.keySet()).append("; ");
                    store.setSongMap(map);
                    mapSeen = true;
                }
            }
            store.noteCall(dump.toString());
            Log.d(TAG, dump.toString());
        }

        @Override protected void afterHookedMethod(MethodHookParam param) {
            if (mapSeen) return;
            Object result = param.getResult();
            if (result != null) {
                store.setSongObject(result);
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static Object readField(Object target, String name) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    // ── store ────────────────────────────────────────────────────────────────────

    /** Thread-safe snapshot of "what MD3Music is playing right now". */
    static final class LyricsStore {
        /** Guards against a stale anchor (e.g. missed updates) pushing the clock far ahead. */
        private static final long MAX_EXTRAPOLATION_MS = 600_000L;

        private final Object lock = new Object();
        private final String packageName;
        private List<LyricMapParser.Line> lines = new ArrayList<>();
        private String title = "";
        private String artist = "";
        private String status = "starting";
        private long durationMs = 0;
        private long anchorPositionMs = 0;
        private long anchorUptimeMs = 0;
        private double anchorSpeed = 1.0d;
        private boolean playing = false;
        private boolean lyricHookReady = false;
        private long revision = 0;
        /** Counts the {@code setSong} calls actually seen: proves the hook is live. */
        private int setSongCount = 0;
        /** Diagnostic dump of the most recent intercepted call, exposed over /v1/health. */
        private volatile String lastCall = "";
        /** Scalar key/values of the most recent setSong Map, for key-name diagnosis. */
        private volatile String lastMeta = "";
        /** True once a Dart Map snapshot has landed; reflective object data must not clobber it. */
        private boolean mapBacked = false;

        LyricsStore(String packageName) {
            this.packageName = packageName;
        }

        void setStatus(String value) {
            synchronized (lock) {
                status = value;
            }
        }

        void markLyricHook(String detail) {
            synchronized (lock) {
                lyricHookReady = true;
                status = "lyric hook: " + detail;
                revision++;
            }
        }

        void noteSetSong() {
            synchronized (lock) {
                setSongCount++;
                status = "lyric hook: setSong x" + setSongCount;
            }
            Log.d(TAG, "setSong received #" + setSongCount);
        }

        void noteCall(String summary) {
            lastCall = summary == null ? "" : (summary.length() > 400 ? summary.substring(0, 400) : summary);
        }

        boolean lyricHookInstalled() {
            synchronized (lock) {
                return lyricHookReady;
            }
        }

        void setSongMap(Map<?, ?> songMap) {
            // Guard against unrelated (Map, String) methods caught by the signature scan.
            if (songMap == null) return;
            if (!songMap.containsKey("lyrics") && !songMap.containsKey("name")) return;

            StringBuilder meta = new StringBuilder();
            for (Map.Entry<?, ?> entry : songMap.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                    meta.append(entry.getKey()).append('=').append(shortValue(value)).append("; ");
                } else {
                    meta.append(entry.getKey()).append('=').append(value == null ? "null" : value.getClass().getSimpleName()).append("; ");
                }
            }
            lastMeta = meta.toString();

            LyricMapParser.SongSnapshot snapshot = LyricMapParser.parse(songMap);
            synchronized (lock) {
                mapBacked = true;
                lines = snapshot.lines;
                title = snapshot.title;
                artist = snapshot.artist;
                durationMs = snapshot.durationMs;
                if (snapshot.startPositionMs > 0) {
                    anchorPositionMs = snapshot.startPositionMs;
                    anchorUptimeMs = SystemClock.elapsedRealtime();
                    anchorSpeed = playing ? 1.0d : 0.0d;
                }
                revision++;
            }
            Log.d(TAG, "song=" + snapshot.title + " lines=" + snapshot.lines.size());
        }

        /**
         * Reflective fallback for builds where only the converted Song object reaches our
         * hook: fields are matched by type order (three Strings, one duration, one lyrics
         * list), which survives R8 field renaming.
         */
        void setSongObject(Object song) {
            if (song == null) return;
            synchronized (lock) {
                if (mapBacked) return;
            }
            List<String> strings = new ArrayList<>();
            List<Number> numbers = new ArrayList<>();
            List<List<?>> lists = new ArrayList<>();
            Class<?> c = song.getClass();
            while (c != null && c != Object.class) {
                Field[] fields;
                try {
                    fields = c.getDeclaredFields();
                } catch (Throwable ignored) {
                    break;
                }
                for (Field field : fields) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(song);
                        if (value == null) continue;
                        if (value instanceof String) strings.add((String) value);
                        else if (value instanceof Number) numbers.add((Number) value);
                        else if (value instanceof List) lists.add((List<?>) value);
                    } catch (Throwable ignored) {
                        // Skip an inaccessible field; the type-order heuristic still works.
                    }
                }
                c = c.getSuperclass();
            }

            String title = strings.isEmpty() ? "" : strings.get(0);
            String artist = strings.size() > 1 ? strings.get(1) : "";
            long duration = 0;
            for (Number number : numbers) {
                duration = Math.max(duration, number.longValue());
            }
            List<LyricMapParser.Line> lines = new ArrayList<>();
            if (!lists.isEmpty()) {
                for (Object element : lists.get(0)) {
                    LyricMapParser.Line line = lineFromObject(element);
                    if (line != null) lines.add(line);
                }
                LyricMapParser.sortLines(lines);
            }

            if (title.isEmpty() && artist.isEmpty() && lines.isEmpty()) return;
            synchronized (lock) {
                this.lines = lines;
                this.title = title;
                this.artist = artist;
                this.durationMs = duration;
                revision++;
            }
            Log.d(TAG, "song(object)=" + title + " lines=" + lines.size());
        }

        private static LyricMapParser.Line lineFromObject(Object element) {
            if (element instanceof Map<?, ?>) {
                return LyricMapParser.lineFromMap((Map<?, ?>) element);
            }
            List<Number> numbers = new ArrayList<>();
            List<String> strings = new ArrayList<>();
            Class<?> c = element.getClass();
            while (c != null && c != Object.class) {
                Field[] fields;
                try {
                    fields = c.getDeclaredFields();
                } catch (Throwable ignored) {
                    break;
                }
                for (Field field : fields) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(element);
                        if (value instanceof Number) numbers.add((Number) value);
                        else if (value instanceof String) strings.add((String) value);
                    } catch (Throwable ignored) {
                        // continue
                    }
                }
                c = c.getSuperclass();
            }
            String text = strings.isEmpty() ? "" : strings.get(0).trim();
            if (text.isEmpty()) return null;
            long begin = numbers.isEmpty() ? 0L : numbers.get(0).longValue();
            long end = numbers.size() > 1 ? numbers.get(1).longValue() : begin + 1;
            if (begin < 0) begin = 0;
            if (end <= begin) end = begin + 1;
            return new LyricMapParser.Line(begin, end, text);
        }

        void setPlayback(PlaybackState state) {
            long position = Math.max(0, state.getPosition());
            double speed = state.getPlaybackSpeed();
            boolean isPlaying = state.getState() == PlaybackState.STATE_PLAYING;
            long stamp = state.getLastPositionUpdateTime();
            synchronized (lock) {
                anchorPositionMs = position;
                anchorUptimeMs = stamp > 0 ? stamp : SystemClock.elapsedRealtime();
                anchorSpeed = isPlaying ? (speed > 0 ? speed : 1.0d) : 0.0d;
                playing = isPlaying;
                revision++;
            }
        }

        void setPlaybackArgs(Long positionMs, Boolean isPlaying) {
            long position = positionMs == null ? 0 : Math.max(0, positionMs);
            boolean playingNow = isPlaying != null && isPlaying;
            synchronized (lock) {
                anchorPositionMs = position;
                anchorUptimeMs = SystemClock.elapsedRealtime();
                anchorSpeed = playingNow ? 1.0d : 0.0d;
                playing = playingNow;
                revision++;
            }
        }

        String snapshotJson() {
            List<LyricMapParser.Line> lines;
            String title;
            String artist;
            long durationMs;
            long positionMs;
            boolean playing;
            long revision;

            synchronized (lock) {
                lines = this.lines;
                title = this.title;
                artist = this.artist;
                durationMs = this.durationMs;
                playing = this.playing;
                revision = this.revision;

                long position = anchorPositionMs;
                if (anchorSpeed > 0) {
                    long delta = SystemClock.elapsedRealtime() - anchorUptimeMs;
                    if (delta > 0 && delta < MAX_EXTRAPOLATION_MS) {
                        position += (long) (delta * anchorSpeed);
                    }
                }
                if (durationMs > 0 && position > durationMs) position = durationMs;
                positionMs = position;
            }

            JSONObject root = new JSONObject();
            try {
                root.put("revision", revision);
                root.put("isPlaying", playing);
                root.put("positionMs", positionMs);
                root.put("durationMs", durationMs);
                root.put("title", title);
                root.put("artist", artist);
                JSONArray array = new JSONArray();
                for (LyricMapParser.Line line : lines) {
                    JSONObject item = new JSONObject();
                    item.put("b", line.begin);
                    item.put("e", line.end);
                    item.put("t", line.text);
                    array.put(item);
                }
                root.put("lines", array);
            } catch (Throwable error) {
                Log.w(TAG, "could not serialise snapshot", error);
            }
            return root.toString();
        }

        String healthJson() {
            JSONObject root = new JSONObject();
            synchronized (lock) {
                try {
                    root.put("ok", true);
                    root.put("package", packageName);
                    root.put("status", status);
                    root.put("lyricHook", lyricHookReady);
                    root.put("setSong", setSongCount);
                    root.put("lines", lines.size());
                    root.put("revision", revision);
                    root.put("isPlaying", playing);
                    root.put("lastCall", lastCall);
                    root.put("lastMeta", lastMeta);
                } catch (Throwable error) {
                    Log.w(TAG, "could not serialise health", error);
                }
            }
            return root.toString();
        }
    }

    // ── loopback server ──────────────────────────────────────────────────────────

    /** Read-only HTTP server bound to 127.0.0.1 so nothing leaves the phone. */
    static final class LocalLyricsServer {
        static final int PORT = 21771;
        private final LyricsStore store;

        LocalLyricsServer(LyricsStore store) {
            this.store = store;
        }

        void start() {
            // 插件在 WebView 里请求的是 http://127.0.0.1:21771（IPv4）。Android 上
            // InetAddress.getLoopbackAddress() 会解析成 [::1]，只监听 IPv6 回环时
            // IPv4 连接会被直接拒绝，所以两个回环地址都监听一遍。
            listen("127.0.0.1", true);
            listen("::1", false);
        }

        private void listen(String host, boolean required) {
            Thread server = new Thread(() -> acceptLoop(host, required), "G2LyricsBridge-" + host);
            server.setDaemon(true);
            server.start();
        }

        private void acceptLoop(String host, boolean required) {
            ServerSocket socket;
            try {
                socket = new ServerSocket();
            } catch (Throwable error) {
                Log.e(TAG, "bridge socket create failed", error);
                return;
            }
            try {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(InetAddress.getByName(host), PORT));
                Log.i(TAG, "bridge bound to " + host + ":" + PORT);
            } catch (Throwable error) {
                Log.e(TAG, "bridge bind failed on " + host, error);
                closeQuietly(socket);
                if (required) {
                    Log.e(TAG, "primary loopback listener unavailable; plugin cannot connect");
                }
                return;
            }
            try {
                while (true) {
                    Socket client = socket.accept();
                    Thread worker = new Thread(() -> handle(client), "G2LyricsBridge-conn");
                    worker.setDaemon(true);
                    worker.start();
                }
            } catch (Throwable error) {
                Log.e(TAG, "bridge server stopped on " + host, error);
            } finally {
                closeQuietly(socket);
            }
        }

        private static void closeQuietly(ServerSocket socket) {
            try {
                socket.close();
            } catch (Throwable ignored) {
                // already gone
            }
        }

        private void handle(Socket client) {
            try (Socket socket = client;
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 OutputStream out = socket.getOutputStream()) {
                String requestLine = reader.readLine();
                int contentLength = 0;
                String header;
                while ((header = reader.readLine()) != null && !header.isEmpty()) {
                    int colon = header.indexOf(':');
                    if (colon > 0 && header.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                        try {
                            contentLength = Integer.parseInt(header.substring(colon + 1).trim());
                        } catch (NumberFormatException ignoredLength) {
                            contentLength = 0;
                        }
                    }
                }
                if (requestLine == null) return;
                String[] parts = requestLine.split(" ");
                String method = parts.length > 0 ? parts[0] : "";
                String path = parts.length > 1 ? parts[1] : "/";

                if ("OPTIONS".equals(method)) {
                    writeResponse(out, "204 No Content", "");
                    return;
                }
                if ("POST".equals(method) && path.startsWith("/v1/log")) {
                    StringBuilder body = new StringBuilder();
                    for (int i = 0; i < contentLength; i++) {
                        int ch = reader.read();
                        if (ch < 0) break;
                        body.append((char) ch);
                    }
                    appendPluginLog(body.toString());
                    writeResponse(out, "204 No Content", "");
                    return;
                }
                if (!"GET".equals(method)) {
                    writeResponse(out, "405 Method Not Allowed", "{\"error\":\"method not allowed\"}");
                    return;
                }
                if (path.startsWith("/v1/logs")) {
                    writeResponse(out, "200 OK", pluginLogsJson());
                    return;
                }
                if (path.startsWith("/v1/lyrics")) {
                    writeResponse(out, "200 OK", store.snapshotJson());
                    return;
                }
                if (path.startsWith("/v1/health")) {
                    writeResponse(out, "200 OK", store.healthJson());
                    return;
                }
                writeResponse(out, "404 Not Found", "{\"error\":\"not found\"}");
            } catch (Throwable ignored) {
                // A dropped connection is normal: the plugin polls every 250 ms.
            }
        }

        /** Ring buffer of what the plugin's WebView reports about itself. */
        private static final int LOG_LIMIT = 200;
        private final List<String> pluginLogs = new ArrayList<>();

        private void appendPluginLog(String raw) {
            if (raw == null || raw.isEmpty()) return;
            String line = raw.length() > 400 ? raw.substring(0, 400) : raw;
            synchronized (pluginLogs) {
                pluginLogs.add(line);
                while (pluginLogs.size() > LOG_LIMIT) pluginLogs.remove(0);
            }
            Log.i(TAG, "plugin: " + line);
        }

        private String pluginLogsJson() {
            JSONArray array = new JSONArray();
            synchronized (pluginLogs) {
                for (String line : pluginLogs) array.put(line);
            }
            return array.toString();
        }

        private void writeResponse(OutputStream out, String status, String body) throws java.io.IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 ").append(status).append("\r\n");
            head.append("Content-Type: application/json; charset=utf-8\r\n");
            head.append("Content-Length: ").append(bytes.length).append("\r\n");
            head.append("Access-Control-Allow-Origin: *\r\n");
            head.append("Access-Control-Allow-Methods: GET, OPTIONS\r\n");
            head.append("Access-Control-Allow-Headers: *\r\n");
            head.append("Cache-Control: no-store\r\n");
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
        }
    }
}
