package com.Mealess.appaction.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits a long plain text into pages of roughly a fixed character budget while
 * preferring paragraph boundaries, and detects chapter headings.
 *
 * Only the source text is kept in memory; pages are produced on demand.
 */
public final class TextEngine {

    public static final class Chapter {
        public final String title;
        public final int offset;
        public Chapter(String title, int offset) {
            this.title = title;
            this.offset = offset;
        }
    }

    private static final Pattern RE_CN = Pattern.compile(
            "^第[0-9０-９一二三四五六七八九十百千万零两廿卅]{1,12}[章回节節卷部篇集话話幕]([\\s:：、.·\\-—]*.*)?$");
    private static final Pattern RE_EN = Pattern.compile(
            "(?i)^(chapter|part|section|book|prologue|epilogue)\\s*[0-9ivxlcdm]{0,8}([\\s:：.\\-—]+.*)?$");
    private static final Pattern RE_SPECIAL = Pattern.compile(
            "^(序章|序言|楔子|引子|前言|后记|後記|尾声|尾聲|番外|终章|終章|大结局|大結局)([\\s:：、.·\\-—].*)?$");

    private final String text;
    private int pageChars;
    private int[] pageStarts;
    private List<Chapter> chapters;
    private int[] chapterPages;

    public TextEngine(String rawText, int pageChars) {
        this.text = normalize(rawText);
        this.pageChars = Math.max(200, pageChars);
        rebuild();
    }

    public static String normalize(String s) {
        if (s == null) return "";
        if (s.length() > 0 && s.charAt(0) == '\uFEFF') s = s.substring(1);
        s = s.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "");
        // Strip control chars that break text rendering
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean bad = (c < 0x20 && c != '\n' && c != '\t') || c == 0x7F;
            if (bad) {
                if (sb == null) sb = new StringBuilder(s.length());
                sb.append(s, 0, i);
                for (int j = i; j < s.length(); j++) {
                    char d = s.charAt(j);
                    if (!((d < 0x20 && d != '\n' && d != '\t') || d == 0x7F)) sb.append(d);
                }
                s = sb.toString();
                break;
            }
        }
        return s;
    }

    private void rebuild() {
        buildPages();
        buildChapters();
    }

    private void buildPages() {
        int len = text.length();
        ArrayList<Integer> starts = new ArrayList<>(Math.max(1, len / Math.max(200, pageChars) + 2));
        starts.add(0);
        int pos = 0;
        while (pos < len) {
            int target = pos + pageChars;
            if (target >= len) break;
            int lower = pos + (pageChars * 3) / 5;
            int upper = Math.min(len - 1, pos + (pageChars * 6) / 5);
            int cut = -1;
            for (int i = upper; i > lower; i--) {
                char c = text.charAt(i);
                if (c == '\n') { cut = i + 1; break; }
                if (cut < 0 && (c == '。' || c == '！' || c == '？' || c == '.' )) { cut = i + 1; }
            }
            if (cut <= pos) cut = target;
            if (cut > len) cut = len;
            starts.add(cut);
            pos = cut;
        }
        int[] arr = new int[starts.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = starts.get(i);
        pageStarts = arr;
    }

    private void buildChapters() {
        List<Chapter> list = new ArrayList<>();
        int len = text.length();
        int lineStart = 0;
        while (lineStart < len) {
            int nl = text.indexOf('\n', lineStart);
            int lineEnd = nl < 0 ? len : nl;
            int a = lineStart, b = lineEnd;
            while (a < b && (text.charAt(a) == ' ' || text.charAt(a) == '\t' || text.charAt(a) == '\u3000')) a++;
            while (b > a && (text.charAt(b - 1) == ' ' || text.charAt(b - 1) == '\t' || text.charAt(b - 1) == '\r')) b--;
            int lineLen = b - a;
            if (lineLen >= 2 && lineLen <= 40) {
                String line = text.substring(a, b);
                if (looksLikeHeading(line)) list.add(new Chapter(line, lineStart));
            }
            if (nl < 0) break;
            lineStart = nl + 1;
        }
        // Remove duplicate consecutive headings that point at the same offset
        List<Chapter> cleaned = new ArrayList<>(list.size());
        int lastOffset = -1;
        for (Chapter c : list) {
            if (c.offset == lastOffset) continue;
            cleaned.add(c);
            lastOffset = c.offset;
        }
        chapters = Collections.unmodifiableList(cleaned);
        int[] pages = new int[chapters.size()];
        for (int i = 0; i < pages.length; i++) pages[i] = pageForOffset(chapters.get(i).offset);
        chapterPages = pages;
    }

    private static boolean looksLikeHeading(String line) {
        if (line.length() < 2 || line.length() > 40) return false;
        char last = line.charAt(line.length() - 1);
        if (last == '。' || last == '，' || last == ',' || last == '；' || last == ';') return false;
        if (RE_CN.matcher(line).matches()) return true;
        if (RE_SPECIAL.matcher(line).matches()) return true;
        if (line.length() <= 32 && RE_EN.matcher(line).matches() && containsDigitOrRoman(line)) return true;
        return false;
    }

    private static boolean containsDigitOrRoman(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if ((c >= '0' && c <= '9') || c == 'i' || c == 'v' || c == 'x' || c == 'l' || c == 'c' || c == 'd' || c == 'm') {
                return true;
            }
        }
        return false;
    }

    public int pageCount() {
        return pageStarts.length;
    }

    public String page(int index) {
        if (index < 0) index = 0;
        if (index >= pageStarts.length) index = pageStarts.length - 1;
        int start = pageStarts[index];
        int end = index + 1 < pageStarts.length ? pageStarts[index + 1] : text.length();
        if (start >= end) return "";
        return text.substring(start, end);
    }

    public int pageForOffset(int offset) {
        if (pageStarts.length == 0) return 0;
        if (offset <= 0) return 0;
        int lo = 0, hi = pageStarts.length - 1, res = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (pageStarts[mid] <= offset) { res = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return res;
    }

    public int offsetForPage(int page) {
        if (pageStarts.length == 0) return 0;
        if (page < 0) page = 0;
        if (page >= pageStarts.length) page = pageStarts.length - 1;
        return pageStarts[page];
    }

    public int charCount() {
        return text.length();
    }

    public String text() {
        return text;
    }

    public int getPageChars() {
        return pageChars;
    }

    /** Re-paginate with a new character budget. Returns the page holding {@code anchorOffset}. */
    public int reflow(int newPageChars, int anchorOffset) {
        int np = Math.max(200, newPageChars);
        if (np != pageChars || pageStarts == null || pageStarts.length == 0) {
            pageChars = np;
            buildPages();
            int[] pages = new int[chapters.size()];
            for (int i = 0; i < pages.length; i++) pages[i] = pageForOffset(chapters.get(i).offset);
            chapterPages = pages;
        }
        return pageForOffset(anchorOffset);
    }

    public List<Chapter> chapters() {
        return chapters;
    }

    public int chapterPage(int index) {
        if (chapterPages.length == 0) return 0;
        if (index < 0) index = 0;
        if (index >= chapterPages.length) index = chapterPages.length - 1;
        return chapterPages[index];
    }

    public int pageChapter(int page) {
        if (chapterPages.length == 0) return -1;
        int res = 0;
        for (int i = 0; i < chapterPages.length; i++) {
            if (chapterPages[i] <= page) res = i;
            else break;
        }
        return res;
    }

    /** Find pages containing the query (lower case), up to {@code max} hits. */
    public List<Integer> find(String query, int max) {
        List<Integer> hits = new ArrayList<>();
        if (query == null || query.isEmpty()) return hits;
        String q = query.toLowerCase(java.util.Locale.ROOT);
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        int from = 0;
        while (hits.size() < max) {
            int idx = lower.indexOf(q, from);
            if (idx < 0) break;
            hits.add(pageForOffset(idx));
            from = idx + Math.max(1, q.length());
        }
        return hits;
    }

    /** A short snippet around a query occurrence, for search results. */
    public String snippetFor(int page, String query) {
        String p = page(page);
        if (query == null || query.isEmpty()) return p.length() > 80 ? p.substring(0, 80) : p;
        String lower = p.toLowerCase(java.util.Locale.ROOT);
        int idx = lower.indexOf(query.toLowerCase(java.util.Locale.ROOT));
        if (idx < 0) return p.length() > 80 ? p.substring(0, 80) : p;
        int start = Math.max(0, idx - 28);
        int end = Math.min(p.length(), idx + query.length() + 50);
        String s = p.substring(start, end).replace('\n', ' ');
        return (start > 0 ? "…" : "") + s + (end < p.length() ? "…" : "");
    }

    /** The chapter heading active at the given page, or "" when the text has no chapters. */
    public String chapterTitleAt(int page) {
        int ci = pageChapter(page);
        if (ci < 0) return "";
        return chapters.get(ci).title;
    }

    /** Search helper: case-insensitive page match used by the reader index. */
    public boolean pageMatches(int page, String queryLower) {
        return page(page).toLowerCase(java.util.Locale.ROOT).contains(queryLower);
    }
}
