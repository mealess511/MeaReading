package com.Mealess.appaction.parser;

import com.Mealess.appaction.core.EpubBook;
import com.Mealess.appaction.core.HtmlText;

import java.io.File;
import java.util.List;

/** EPUB reader: renders chapters in a WebView with resources served from the zip. */
public class EpubParser extends AbstractParser {

    private final EpubBook book;
    private String lastText;
    private int lastTextIndex = -1;

    public EpubParser(File file) throws Exception {
        this.book = new EpubBook(file);
        if (book.size() == 0) throw new Exception("EPUB 中没有找到章节");
    }

    public EpubBook book() {
        return book;
    }

    @Override public boolean isHtml() { return true; }
    @Override public int pageCount() { return book.size(); }
    @Override public String html(int page) { return book.html(page); }
    @Override public String baseUrl(int page) { return book.baseUrl(page); }
    @Override public int chapterCount() { return book.size(); }
    @Override public String chapterTitle(int chapter) {
        List<EpubBook.Chapter> list = book.chapters();
        return chapter >= 0 && chapter < list.size() ? list.get(chapter).title : "";
    }
    @Override public int chapterPage(int chapter) { return Math.max(0, Math.min(chapter, book.size() - 1)); }
    @Override public int pageChapter(int page) { return page; }
    @Override public String format() { return "epub"; }

    @Override
    public CharSequence text(int page) {
        if (page == lastTextIndex && lastText != null) return lastText;
        String t = HtmlText.strip(book.html(page));
        lastText = t;
        lastTextIndex = page;
        return t;
    }

    @Override
    public List<Integer> search(String query, int max) {
        List<Integer> hits = new java.util.ArrayList<>();
        if (query == null || query.isEmpty()) return hits;
        String q = query.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < book.size() && hits.size() < max; i++) {
            if (text(i).toString().toLowerCase(java.util.Locale.ROOT).contains(q)) hits.add(i);
        }
        return hits;
    }

    @Override
    public String snippet(int page, String query) {
        String t = text(page).toString().replace('\n', ' ');
        if (query == null || query.isEmpty()) return t.length() > 90 ? t.substring(0, 90) : t;
        int idx = t.toLowerCase(java.util.Locale.ROOT).indexOf(query.toLowerCase(java.util.Locale.ROOT));
        if (idx < 0) return t.length() > 90 ? t.substring(0, 90) : t;
        int start = Math.max(0, idx - 26);
        int end = Math.min(t.length(), idx + query.length() + 54);
        return (start > 0 ? "…" : "") + t.substring(start, end) + (end < t.length() ? "…" : "");
    }

    @Override
    public void close() {
        book.close();
    }
}
