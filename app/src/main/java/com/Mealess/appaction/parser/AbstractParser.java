package com.Mealess.appaction.parser;

import android.graphics.Bitmap;

import java.util.Collections;
import java.util.List;

/** No-op defaults so concrete parsers only override what they support. */
public abstract class AbstractParser implements Parser {

    @Override public boolean isImage() { return false; }
    @Override public boolean isHtml() { return false; }
    @Override public CharSequence text(int page) { return ""; }
    @Override public Bitmap image(int page, int width, int height) { return null; }
    @Override public String html(int page) { return ""; }
    @Override public String baseUrl(int page) { return ""; }
    @Override public int chapterCount() { return 0; }
    @Override public String chapterTitle(int chapter) { return ""; }
    @Override public int chapterPage(int chapter) { return 0; }
    @Override public int pageChapter(int page) { return -1; }
    @Override public int reflow(int pageChars, int anchorOffset) { return -1; }
    @Override public int pageForOffset(int offset) { return -1; }
    @Override public int offsetForPage(int page) { return -1; }
    @Override public List<Integer> search(String query, int max) { return Collections.emptyList(); }
    @Override public String snippet(int page, String query) { return ""; }
    @Override public String format() { return ""; }
    @Override public void close() {}
}
