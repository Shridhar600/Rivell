package com.rivell.workbench.platform.parsing;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.rivell.workbench.contracts.DocumentNode;
import com.rivell.workbench.contracts.ParseCoverage;
import com.rivell.workbench.contracts.ParseCoverage.Gap;
import com.rivell.workbench.contracts.ParseCoverage.Location;
import com.rivell.workbench.contracts.ParseCoverage.LocationTotal;

/**
 * Compares an independent read of the raw file with the parsed nodes, word by word.
 *
 * <p>Words of segments the structure is meant to hold are aligned with the captured text in order
 * (Myers shortest edit script), so a word counts as captured only where the structure holds it in
 * sequence. Text in parts the structure does not represent (headers, footers, notes, comments,
 * drawings) is reported as gaps whole.
 * When the two sides differ by more than {@link #MAX_EDITS} words the alignment stops and every
 * paragraph is matched whole against the node text instead ({@code exact = false}).
 */
final class TextCoverage {

    static final int MAX_EDITS = 3000;

    private TextCoverage() {
    }

    static ParseCoverage measure(List<DocxTextInventory.Segment> source, List<DocumentNode> nodes) {
        return measureTexts(source, nodes.stream().map(DocumentNode::text).toList());
    }

    /** {@code captured}: the structure's text pieces in source order (nulls are skipped). */
    static ParseCoverage measureTexts(List<DocxTextInventory.Segment> source, List<String> captured) {
        var ids = new HashMap<String, Integer>();
        var mainWords = new ArrayList<String>();
        var mainSegment = new ArrayList<Integer>();
        for (int s = 0; s < source.size(); s++) {
            if (source.get(s).aligned()) {
                for (var word : words(source.get(s).text())) {
                    mainWords.add(word);
                    mainSegment.add(s);
                }
            }
        }
        var nodeWords = new ArrayList<String>();
        for (var text : captured) {
            nodeWords.addAll(words(text));
        }
        var a = intern(mainWords, ids);
        var b = intern(nodeWords, ids);

        var matched = align(a, b);
        var exact = matched != null;
        int extra;
        if (exact) {
            int alignedWords = 0;
            for (var m : matched) {
                alignedWords += m ? 1 : 0;
            }
            extra = b.length - alignedWords;
        } else {
            matched = paragraphMatch(source, mainSegment, String.join(" ", nodeWords));
            extra = 0;
        }

        var sourceTotals = new EnumMap<Location, Integer>(Location.class);
        var capturedTotals = new EnumMap<Location, Integer>(Location.class);
        var gaps = new ArrayList<Gap>();
        var run = new GapRun();
        for (int i = 0; i < a.length; i++) {
            var segment = source.get(mainSegment.get(i));
            sourceTotals.merge(segment.location(), 1, Integer::sum);
            if (matched[i]) {
                capturedTotals.merge(segment.location(), 1, Integer::sum);
                run.close(gaps);
            } else {
                run.add(segment, mainWords.get(i), gaps);
            }
        }
        run.close(gaps);
        for (var segment : source) {
            if (segment.aligned()) {
                continue;
            }
            for (var word : words(segment.text())) {
                sourceTotals.merge(segment.location(), 1, Integer::sum);
                run.add(segment, word, gaps);
            }
        }
        run.close(gaps);

        var totals = new ArrayList<LocationTotal>();
        for (var entry : sourceTotals.entrySet()) {
            totals.add(new LocationTotal(entry.getKey(), entry.getValue(),
                    capturedTotals.getOrDefault(entry.getKey(), 0)));
        }
        return new ParseCoverage(ParseCoverage.CHECK_VERSION, exact, totals, extra, gaps);
    }

    static List<String> words(String text) {
        if (text == null) {
            return List.of();
        }
        var normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("[\\u200B-\\u200D\\uFEFF\\u00AD]", "");
        var out = new ArrayList<String>();
        for (var word : normalized.split("\\s+")) {
            if (!word.isEmpty()) {
                out.add(word);
            }
        }
        return out;
    }

    private static int[] intern(List<String> words, Map<String, Integer> ids) {
        var out = new int[words.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ids.computeIfAbsent(words.get(i), key -> ids.size());
        }
        return out;
    }

    /**
     * Myers' greedy shortest edit script; returns which words of {@code a} align with {@code b},
     * or null when more than {@link #MAX_EDITS} edits are needed. Each step keeps only the
     * diagonals it can reach, so memory grows with the square of the edit count, not the length.
     */
    static boolean[] align(int[] a, int[] b) {
        int n = a.length;
        int m = b.length;
        int off = MAX_EDITS + 2;
        var v = new int[2 * off + 1];
        java.util.Arrays.fill(v, -1);
        v[off + 1] = 0;
        var trace = new ArrayList<int[]>();
        for (int d = 0; d <= Math.min(MAX_EDITS, n + m); d++) {
            trace.add(java.util.Arrays.copyOfRange(v, off - d - 1, off + d + 2));
            for (int k = -d; k <= d; k += 2) {
                int x = k == -d || (k != d && v[off + k - 1] < v[off + k + 1])
                        ? v[off + k + 1] : v[off + k - 1] + 1;
                int y = x - k;
                while (x < n && y < m && a[x] == b[y]) {
                    x++;
                    y++;
                }
                v[off + k] = x;
                if (x >= n && y >= m) {
                    return backtrack(trace, n, m);
                }
            }
        }
        return null;
    }

    private static boolean[] backtrack(List<int[]> trace, int n, int m) {
        var matched = new boolean[n];
        int x = n;
        int y = m;
        for (int d = trace.size() - 1; d >= 0; d--) {
            var v = trace.get(d);
            int k = x - y;
            int prevK;
            if (k == -d || (k != d && v[k - 1 + d + 1] < v[k + 1 + d + 1])) {
                prevK = k + 1;
            } else {
                prevK = k - 1;
            }
            int prevX = v[prevK + d + 1];
            int prevY = prevX - prevK;
            while (x > prevX && y > prevY) {
                matched[x - 1] = true;
                x--;
                y--;
            }
            x = prevX;
            y = prevY;
        }
        return matched;
    }

    /** Fallback: a main-document paragraph counts as captured when its words appear in the nodes. */
    private static boolean[] paragraphMatch(
            List<DocxTextInventory.Segment> source, List<Integer> mainSegment, String nodeText) {
        var matched = new boolean[mainSegment.size()];
        var cache = new HashMap<Integer, Boolean>();
        for (int i = 0; i < matched.length; i++) {
            int segment = mainSegment.get(i);
            matched[i] = cache.computeIfAbsent(segment, s ->
                    nodeText.contains(String.join(" ", words(source.get(s).text()))));
        }
        return matched;
    }

    /** Collects consecutive missing words of one part and location into a gap. */
    private static final class GapRun {

        private String part;
        private Location location;
        private int words;
        private final StringBuilder excerpt = new StringBuilder();

        void add(DocxTextInventory.Segment segment, String word, List<Gap> gaps) {
            if (words > 0 && (!segment.part().equals(part) || segment.location() != location)) {
                close(gaps);
            }
            part = segment.part();
            location = segment.location();
            words++;
            if (excerpt.length() < Gap.EXCERPT_CHARS) {
                if (!excerpt.isEmpty()) {
                    excerpt.append(' ');
                }
                excerpt.append(word);
            }
        }

        void close(List<Gap> gaps) {
            if (words > 0) {
                var text = excerpt.length() > Gap.EXCERPT_CHARS
                        ? excerpt.substring(0, Gap.EXCERPT_CHARS) : excerpt.toString();
                gaps.add(new Gap(part, location, words, text));
            }
            words = 0;
            excerpt.setLength(0);
        }
    }
}
