package com.personal.g2lyricsbridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain-JVM check for {@link LyricMapParser} — the only part of the module that has no
 * Android dependency. It mirrors the Map that MD3Music's Dart layer sends over the
 * {@code com.md3music.md3music/lyricon} channel (values arrive as Double/Long/Integer
 * depending on how the platform channel encoded them, so the parser must not cast).
 *
 * <p>Run: javac -d <out> LyricMapParser.java LyricMapParserTest.java && java -cp <out>
 * com.personal.g2lyricsbridge.LyricMapParserTest
 */
public final class LyricMapParserTest {

    public static void main(String[] args) {
        int failures = 0;
        failures += checkSortedAndFiltered();
        failures += checkMetadata();
        failures += checkGarbageGuard();
        failures += checkUnsortedInput();
        if (failures == 0) {
            System.out.println("LyricMapParserTest: all checks passed");
        } else {
            System.out.println("LyricMapParserTest: " + failures + " check(s) failed");
            System.exit(1);
        }
    }

    private static int checkSortedAndFiltered() {
        Map<String, Object> song = new LinkedHashMap<>();
        song.put("name", "晴天");
        song.put("lyrics", Arrays.asList(
                line(12000D, 15000D, "第三句"),
                line(0D, 2000D, "  "),
                line(4000D, 8000D, "第二句"),
                line(1000D, 3000D, "第一句")));
        LyricMapParser.SongSnapshot snapshot = LyricMapParser.parse(song);
        if (snapshot.lines.size() != 3) return fail("blank line not filtered: " + snapshot.lines.size());
        if (!"第一句".equals(snapshot.lines.get(0).text)) return fail("not sorted ascending");
        if (snapshot.lines.get(0).begin != 1000L) return fail("begin not parsed from Double");
        if (!"第三句".equals(snapshot.lines.get(2).text)) return fail("last line wrong");
        return 0;
    }

    private static int checkMetadata() {
        Map<String, Object> song = new LinkedHashMap<>();
        song.put("name", "晴天");
        song.put("artist", "周杰伦");
        song.put("duration", 269000L);
        song.put("startPositionMs", 42000L);
        song.put("lyrics", new ArrayList<Object>());
        LyricMapParser.SongSnapshot snapshot = LyricMapParser.parse(song);
        if (!"晴天".equals(snapshot.title)) return fail("title");
        if (!"周杰伦".equals(snapshot.artist)) return fail("artist");
        if (snapshot.durationMs != 269000L) return fail("duration");
        if (snapshot.startPositionMs != 42000L) return fail("startPositionMs");
        return 0;
    }

    private static int checkGarbageGuard() {
        Map<String, Object> song = new HashMap<>();
        song.put("unrelated", true);
        LyricMapParser.SongSnapshot snapshot = LyricMapParser.parse(song);
        if (!snapshot.lines.isEmpty()) return fail("unrelated map produced lines");
        if (snapshot.startPositionMs != 0L || snapshot.durationMs != 0L) return fail("defaults");
        return 0;
    }

    private static int checkUnsortedInput() {
        List<Object> raw = new ArrayList<>();
        raw.add(line(9000D, 9000D, "同刻"));     // end == begin must be widened to a positive window
        raw.add(line(3000D, 5000D, "早"));
        LyricMapParser.SongSnapshot snapshot = LyricMapParser.parse(songWith(raw));
        if (snapshot.lines.size() != 2) return fail("line count");
        LyricMapParser.Line widened = snapshot.lines.get(1);
        if (widened.end <= widened.begin) return fail("zero-length line not widened");
        return 0;
    }

    private static Map<String, Object> songWith(List<Object> lyrics) {
        Map<String, Object> song = new LinkedHashMap<>();
        song.put("lyrics", lyrics);
        return song;
    }

    private static Map<String, Object> line(double begin, double end, String text) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("begin", begin);
        line.put("end", end);
        line.put("text", text);
        return line;
    }

    private static int fail(String message) {
        System.out.println("FAIL: " + message);
        return 1;
    }
}
