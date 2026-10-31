package com.Mealess.appaction.parser;

import android.graphics.Bitmap;

import java.util.List;

/** Unified reading interface implemented by every supported format. */
public interface Parser {

    boolean isImage();

    boolean isHtml();

    int pageCount();

    CharSequence text(int page);

    Bitmap image(int page, int width, int height);

    String html(int page);

    String baseUrl(int page);

    int chapterCount();

    String chapterTitle(int chapter);

    int chapterPage(int chapter);

    int pageChapter(int page);

    /** Re-paginates for a new character budget. Returns the page holding {@code anchorOffset}, or -1. */
    int reflow(int pageChars, int anchorOffset);

    int pageForOffset(int offset);

    int offsetForPage(int page);

    List<Integer> search(String query, int max);

    String snippet(int page, String query);

    String format();

    void close();
}
