package com.Mealess.appaction.parser;

import com.Mealess.appaction.core.TextEngine;

import java.util.List;

/** Plain-text reading (TXT / MD / HTML / FB2 / MOBI all end up here). */
public class TextParser extends AbstractParser {

    protected final TextEngine engine;
    protected final String format;

    public TextParser(String text, int pageChars, String format) {
        this.engine = new TextEngine(text, pageChars);
        this.format = format == null ? "" : format;
    }

    public TextEngine engine() {
        return engine;
    }

    @Override public int pageCount() { return engine.pageCount(); }
    @Override public CharSequence text(int page) { return engine.page(page); }
    @Override public int chapterCount() { return engine.chapters().size(); }
    @Override public String chapterTitle(int chapter) {
        List<TextEngine.Chapter> list = engine.chapters();
        if (chapter < 0 || chapter >= list.size()) return "";
        return list.get(chapter).title;
    }
    @Override public int chapterPage(int chapter) { return engine.chapterPage(chapter); }
    @Override public int pageChapter(int page) { return engine.pageChapter(page); }
    @Override public int reflow(int pageChars, int anchorOffset) { return engine.reflow(pageChars, anchorOffset); }
    @Override public int pageForOffset(int offset) { return engine.pageForOffset(offset); }
    @Override public int offsetForPage(int page) { return engine.offsetForPage(page); }
    @Override public List<Integer> search(String query, int max) { return engine.find(query, max); }
    @Override public String snippet(int page, String query) { return engine.snippetFor(page, query); }
    @Override public String format() { return format; }
}
