package com.Mealess.appaction.parser;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.Mealess.appaction.core.ComicArchive;
import com.Mealess.appaction.core.Io;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Comic reader for CBZ / CBR / CBT and image-only zip files. */
public class ComicParser extends AbstractParser {

    private final ComicArchive archive;
    private final String format;
    private final List<Integer> chapterStarts = new ArrayList<>();
    private final List<String> chapterNames = new ArrayList<>();

    public ComicParser(ComicArchive archive, String format) throws Exception {
        this.archive = archive;
        this.format = format == null ? "" : format;
        if (archive.size() == 0) throw new Exception("压缩包中没有图片");
        buildChapters();
    }

    private void buildChapters() {
        String lastDir = null;
        for (int i = 0; i < archive.size(); i++) {
            String name = archive.name(i);
            String dir = parentOf(name);
            if (lastDir == null || !dir.equals(lastDir)) {
                chapterStarts.add(i);
                chapterNames.add(dir.isEmpty() ? "第 " + (chapterNames.size() + 1) + " 卷" : dir);
                lastDir = dir;
            }
        }
    }

    private static String parentOf(String name) {
        String n = name == null ? "" : name.replace('\\', '/');
        int idx = n.lastIndexOf('/');
        if (idx <= 0) return "";
        String dir = n.substring(0, idx);
        int slash = dir.lastIndexOf('/');
        return slash >= 0 ? dir.substring(slash + 1) : dir;
    }

    @Override public boolean isImage() { return true; }
    @Override public int pageCount() { return archive.size(); }
    @Override public String format() { return format; }

    @Override public int chapterCount() { return chapterNames.size(); }
    @Override public String chapterTitle(int chapter) {
        return chapter >= 0 && chapter < chapterNames.size() ? chapterNames.get(chapter) : "";
    }
    @Override public int chapterPage(int chapter) {
        if (chapterStarts.isEmpty()) return 0;
        return chapterStarts.get(Math.max(0, Math.min(chapter, chapterStarts.size() - 1)));
    }
    @Override public int pageChapter(int page) {
        if (chapterStarts.isEmpty()) return -1;
        int res = 0;
        for (int i = 0; i < chapterStarts.size(); i++) {
            if (chapterStarts.get(i) <= page) res = i;
            else break;
        }
        return res;
    }

    @Override
    public Bitmap image(int page, int width, int height) {
        if (page < 0 || page >= archive.size()) return null;
        int targetW = Math.max(320, Math.min(width, 2048));
        InputStream in = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            in = archive.open(page);
            BitmapFactory.decodeStream(in, null, bounds);
            Io.closeQuietly(in);
            in = null;
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = (bounds.outWidth / sample) * (bounds.outHeight / sample) > 3_000_000
                    ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
            in = archive.open(page);
            return BitmapFactory.decodeStream(in, null, opts);
        } catch (Throwable t) {
            return null;
        } finally {
            Io.closeQuietly(in);
        }
    }

    public String pageName(int page) {
        return page >= 0 && page < archive.size() ? archive.name(page) : "";
    }

    @Override
    public void close() {
        try { archive.close(); } catch (Throwable ignored) {}
    }

    public static ComicParser open(File file, String format, File cacheBase) throws Exception {
        return new ComicParser(ComicArchive.open(file, format, cacheBase), format);
    }
}
