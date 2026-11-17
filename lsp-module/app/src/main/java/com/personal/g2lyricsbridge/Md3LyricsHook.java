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
 * <p>Three hook strategies are used on purpose, in fallback order:
 *
 * <ul>
 *   <li><b>Channel dispatcher</b> (primary). MD3Music is a Flutter app: Dart calls
 *       {@code MethodChannel.invokeMethod('setSong', {'song': …})}, and the native dispatcher
 *       receives that raw Map before any Kotlin conversion. Release builds inline the Kotlin
 *       builder away, but the dispatcher itself cannot disappear — it is the entry point.</li>
 *   <li><b>Named builder</b>: {@code AudioPlaybackService$Companion} exists only in debug
 *       builds; hooked when present.</li>
 *   <li><b>Signature scan</b>: any method shaped like {@code (Map, String) -> Song} found on
 *       the {@code AudioPlaybackService} instance or its nested classes.</li>
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

    /** Fast path: present when the build did not obfuscate app classes (debug builds). */
    private static final String NAMED_COMPANION = "com.md3music.md3music.AudioPlaybackService$Companion";

    /**
     * Release 6.3.0 (versionCode 2055) merges every Flutter channel handler into one
     * obfuscated "kitchen sink" class. Dumping that APK shows the dispatcher living at
     * {@code androidx.lifecycle.m#S(MethodCall, Result)}, handling {@code setSong},
     * {@code setPlaybackState} and {@code sendLyric}. Hooking this entry point is the only
     * way to reach the raw song map once R8 has inlined {@code buildLyriconSong}: the
     * dispatcher still receives Dart's original {@code Map} before any Kotlin conversion.
     */
    private static final String[] DISPATCHER_CANDIDATES = {"androidx.lifecycle.m"};

    private static volatile boolean booted;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGES.contains(lpparam.packageName)) return;
        synchronized (Md3LyricsHook.class) {
            if (booted) return;
            booted = true;
        }

        LyricsStore store = new LyricsStore(lpparam.packageName);
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
        // Strategy 1: the channel dispatcher. Survives R8 because it is the entry point Dart
        // actually calls, so the raw Map reaches us before any (possibly inlined) builder runs.
        if (hookDispatcher(lpparam.classLoader, store)) return;

        Class<?> named = XposedHelpers.findClassIfExists(NAMED_COMPANION, lpparam.classLoader);
        if (named != null && installOn(named, store, "named")) return;

        // Obfuscated build (or the class is not loaded yet): wait for the service instance
        // and look for the (Map, String) -> Song builder inside it and its nested classes.
        try {
            XposedHelpers.findAndHookMethod("android.app.Service", lpparam.classLoader, "onCreate",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object service = param.thisObject;
                            if (service == null) return;
                            discover(service.getClass(), store);
                        }
                    });
            store.setStatus("waiting for AudioPlaybackService");
            XposedBridge.log(TAG + ": lyric hook waiting for service creation");
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": Service.onCreate hook failed: " + error);
            store.setStatus("hook failed: " + error);
        }
    }

    private void discover(Class<?> serviceClass, LyricsStore store) {
        if (store.lyricHookInstalled()) return;
        List<Class<?>> candidates = new ArrayList<>();
        candidates.add(serviceClass);
        Collections.addAll(candidates, serviceClass.getDeclaredClasses());
        for (Class<?> candidate : candidates) {
            if (installOn(candidate, store, "service")) return;
        }
        // Keep the reason visible in /v1/health: it is the only remote diagnostic
        // available when the user cannot attach adb.
        store.setStatus("scanned " + candidates.size() + " class(es) under "
                + serviceClass.getSimpleName() + ", no (Map,String) lyric builder");
        XposedBridge.log(TAG + ": scanned " + serviceClass.getName() + " and "
                + (candidates.size() - 1) + " nested class(es); no lyric builder found");
    }

    /** Hooks every {@code (Map, String) -> Song} shaped method in {@code holder}. */
    private boolean installOn(Class<?> holder, LyricsStore store, String how) {
        boolean installed = false;
        try {
            for (Method method : holder.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 2
                        && params[0] == Map.class
                        && params[1] == String.class
                        && method.getReturnType() != Void.TYPE) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Object argument = param.args[0];
                            if (argument instanceof Map<?, ?>) {
                                store.setSongMap((Map<?, ?>) argument);
                            }
                        }
                    });
                    installed = true;
                } else if (params.length == 2
                        && params[0] == Long.TYPE
                        && params[1] == Boolean.TYPE
                        && PlaybackState.class == method.getReturnType()) {
                    // Fallback for playback state when the framework hook is unavailable.
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            store.setPlaybackArgs((Long) param.args[0], (Boolean) param.args[1]);
                        }
                    });
                }
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": scan of " + holder.getName() + " failed: " + error);
        }
        if (installed) {
            store.markLyricHook(how + ":" + holder.getName());
            XposedBridge.log(TAG + ": lyric hook installed on " + holder.getName());
        }
        return installed;
    }

    // ── channel dispatcher (obfuscated releases) ─────────────────────────────────

    /**
     * Hooks every channel-dispatch method of a candidate class: {@code void (call, result)}
     * where {@code result} exposes the Flutter {@code Result} shape ({@code success(Object)}
     * plus {@code error(String, String, Object)}). Nothing here depends on the obfuscated
     * method or field names, only on that structural shape.
     */
    private boolean hookDispatcher(ClassLoader classLoader, LyricsStore store) {
        boolean installed = false;
        for (String name : DISPATCHER_CANDIDATES) {
            Class<?> holder = XposedHelpers.findClassIfExists(name, classLoader);
            if (holder == null) continue;
            int hits = 0;
            for (Method method : holder.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (method.getReturnType() != Void.TYPE || params.length != 2) continue;
                if (!looksLikeResult(params[1])) continue;
                try {
                    XposedBridge.hookMethod(method, new ChannelDispatcherHook(params[0], store));
                    hits++;
                    installed = true;
                } catch (Throwable error) {
                    XposedBridge.log(TAG + ": dispatcher hook on " + method + " failed: " + error);
                }
            }
            if (hits > 0) {
                store.markLyricHook("channel dispatcher " + holder.getName() + " (" + hits + " entry)");
                XposedBridge.log(TAG + ": channel dispatcher hooked on " + holder.getName()
                        + " (" + hits + " method(s))");
            }
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
     * a {@code Map} is the argument bundle.
     */
    private static final class ChannelDispatcherHook extends XC_MethodHook {
        private final Field[] fields;
        private final LyricsStore store;

        ChannelDispatcherHook(Class<?> callType, LyricsStore store) {
            this.store = store;
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
        }

        @Override protected void beforeHookedMethod(MethodHookParam param) {
            Object call = param.args[0];
            if (call == null || fields.length == 0) return;
            boolean isSetSong = false;
            Object bundle = null;
            for (Field field : fields) {
                try {
                    Object value = field.get(call);
                    if (value instanceof String && "setSong".equals(value)) {
                        isSetSong = true;
                    } else if (value instanceof Map<?, ?>) {
                        bundle = value;
                    }
                } catch (Throwable ignored) {
                    // Keep scanning: one inaccessible field must not drop the call.
                }
            }
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

        boolean lyricHookInstalled() {
            synchronized (lock) {
                return lyricHookReady;
            }
        }

        void setSongMap(Map<?, ?> songMap) {
            // Guard against unrelated (Map, String) methods caught by the signature scan.
            if (songMap == null) return;
            if (!songMap.containsKey("lyrics") && !songMap.containsKey("name")) return;

            LyricMapParser.SongSnapshot snapshot = LyricMapParser.parse(songMap);
            synchronized (lock) {
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
