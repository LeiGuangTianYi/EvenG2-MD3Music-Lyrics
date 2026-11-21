package com.personal.g2lyricsbridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Parses the plain {@code Map} that MD3Music's Dart layer hands to
 * {@code AudioPlaybackService$Companion.buildLyriconSong}.
 *
 * <p>Why parse the Map instead of the Lyricon {@code Song} object: release builds of
 * MD3Music enable R8 ({@code isMinifyEnabled = true}) with no keep rule for
 * {@code AudioPlaybackService}, its {@code Companion}, {@code buildLyriconSong}, or the
 * {@code io.github.proify.lyricon} model classes, so every class and method name in that
 * path can be renamed. The Map keys, however, are string literals produced by Dart
 * ({@code id / name / artist / duration / lyrics[{begin,end,text}]}) and survive
 * obfuscation untouched.
 */
final class LyricMapParser {

    private LyricMapParser() {
    }

    static final class Line {
        final long begin;
        final long end;
        final String text;

        Line(long begin, long end, String text) {
            this.begin = begin;
            this.end = end;
            this.text = text;
        }
    }

    static final class SongSnapshot {
        final List<Line> lines;
        final String title;
        final String artist;
        final long durationMs;
        final long startPositionMs;

        SongSnapshot(List<Line> lines, String title, String artist, long durationMs, long startPositionMs) {
            this.lines = lines;
            this.title = title;
            this.artist = artist;
            this.durationMs = durationMs;
            this.startPositionMs = startPositionMs;
        }
    }

    static SongSnapshot parse(Map<?, ?> songMap) {
        List<Line> lines = extractLines(songMap == null ? null : songMap.get("lyrics"));

        String title = "";
        String artist = "";
        long duration = 0;
        long startPosition = 0;
        if (songMap != null) {
            title = stringOf(songMap.get("name"));
            artist = stringOf(songMap.get("artist"));
            duration = longOf(songMap.get("duration"));
            startPosition = longOf(songMap.get("startPositionMs"));
        }
        if (duration < 0) duration = 0;
        if (startPosition < 0) startPosition = 0;
        if (duration > 0 && startPosition > duration) startPosition = duration;
        return new SongSnapshot(lines, title, artist, duration, startPosition);
    }

    /** Extracts and sorts lyric lines from the Dart {@code lyrics} array. */
    static List<Line> extractLines(Object rawLyrics) {
        List<Line> lines = new ArrayList<>();
        if (rawLyrics instanceof Iterable<?>) {
            for (Object element : (Iterable<?>) rawLyrics) {
                if (element instanceof Map<?, ?>) {
                    Line line = lineFromMap((Map<?, ?>) element);
                    if (line != null) lines.add(line);
                }
            }
        }
        sortLines(lines);
        return lines;
    }

    /** Builds one line from a Dart line map; returns null for blank/instrumental padding. */
    static Line lineFromMap(Map<?, ?> line) {
        String text = stringOf(line.get("text")).trim();
        if (text.isEmpty()) return null;
        long begin = longOf(line.get("begin"));
        long end = longOf(line.get("end"));
        if (begin < 0) begin = 0;
        if (end <= begin) end = begin + 1;
        return new Line(begin, end, text);
    }

    static void sortLines(List<Line> lines) {
        Collections.sort(lines, new Comparator<Line>() {
            @Override public int compare(Line left, Line right) {
                return Long.compare(left.begin, right.begin);
            }
        });
    }

    private static String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long longOf(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof CharSequence) {
            try {
                return Long.parseLong(value.toString().trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }
}
