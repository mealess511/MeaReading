package com.Mealess.appaction;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** JSON based bookshelf persistence (robust against names containing separators). */
public final class BookStorage {

    private static final String PREF = "shelf_v2";
    private static final String KEY = "books";

    private BookStorage() {}

    public static List<Book> loadBooks(Context c) {
        List<Book> out = new ArrayList<>();
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String data = sp.getString(KEY, null);
        if (data == null || data.isEmpty()) {
            // migrate the legacy pipe separated format once
            out = LegacyStorage.load(c);
            if (!out.isEmpty()) saveBooks(c, out);
            return out;
        }
        try {
            JSONArray arr = new JSONArray(data);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Book b = new Book();
                b.uri = o.optString("uri");
                b.name = o.optString("name");
                b.author = o.optString("author");
                b.format = o.optString("fmt");
                b.coverPath = o.optString("cover");
                b.category = o.optString("cat", "NOVEL");
                b.favorite = o.optBoolean("fav");
                b.lastReadTime = o.optLong("lrt");
                b.totalReadTime = o.optLong("trt");
                b.addedTime = o.optLong("add");
                b.size = o.optLong("size");
                b.lastPage = o.optInt("page");
                b.lastOffset = o.optInt("off");
                b.lastChapter = o.optInt("ch");
                b.progress = (float) o.optDouble("prog", 0);
                b.bookmarks = o.optString("bm", "");
                if (b.uri != null && !b.uri.isEmpty()) out.add(b);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static void saveBooks(Context c, List<Book> books) {
        JSONArray arr = new JSONArray();
        for (Book b : books) {
            JSONObject o = new JSONObject();
            try {
                o.put("uri", b.uri);
                o.put("name", b.name);
                o.put("author", b.author == null ? "" : b.author);
                o.put("fmt", b.format);
                o.put("cover", b.coverPath == null ? "" : b.coverPath);
                o.put("cat", b.category);
                o.put("fav", b.favorite);
                o.put("lrt", b.lastReadTime);
                o.put("trt", b.totalReadTime);
                o.put("add", b.addedTime);
                o.put("size", b.size);
                o.put("page", b.lastPage);
                o.put("off", b.lastOffset);
                o.put("ch", b.lastChapter);
                o.put("prog", b.progress);
                o.put("bm", b.bookmarks == null ? "" : b.bookmarks);
            } catch (Throwable ignored) {}
            arr.put(o);
        }
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply();
    }

    /** Reads the bookshelf written by the previous version of the app. */
    static final class LegacyStorage {
        static List<Book> load(Context c) {
            List<Book> books = new ArrayList<>();
            SharedPreferences sp = c.getSharedPreferences("bookshelf_data", Context.MODE_PRIVATE);
            String data = sp.getString("all_books", "");
            if (data == null || data.isEmpty()) return books;
            for (String line : data.split("\n")) {
                if (line.trim().isEmpty()) continue;
                String[] p = line.split("\\|\\|", -1);
                if (p.length < 8) continue;
                try {
                    Book b = new Book(p[0], p[1], p[2], p[4]);
                    b.coverPath = p[3].isEmpty() ? null : p[3];
                    b.favorite = Boolean.parseBoolean(p[5]);
                    b.lastReadTime = Long.parseLong(p[6]);
                    b.lastPage = Integer.parseInt(p[7]);
                    if (p.length >= 9) b.bookmarks = p[8];
                    if (p.length >= 10) b.totalReadTime = Long.parseLong(p[9]);
                    books.add(b);
                } catch (Throwable ignored) {}
            }
            return books;
        }
    }
}
