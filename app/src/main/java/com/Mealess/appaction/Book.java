package com.Mealess.appaction;

/** A book on the shelf. Persisted through {@link BookStorage}. */
public class Book {

    public String uri;
    public String name;
    public String author;
    public String format;
    public String coverPath;
    public String category;      // NOVEL or COMIC
    public boolean favorite;
    public long lastReadTime;
    public long totalReadTime;   // milliseconds
    public long addedTime;
    public long size;
    public int lastPage;
    public int lastOffset;       // character offset, text books only
    public int lastChapter;
    public float progress;       // 0..1, format independent reading position
    public String bookmarks = ""; // JSON array of bookmark objects

    public Book() {
        addedTime = System.currentTimeMillis();
    }

    public Book(String uri, String name, String format, String category) {
        this();
        this.uri = uri;
        this.name = name;
        this.format = format == null ? "" : format;
        this.category = category == null ? "NOVEL" : category;
    }

    public boolean isComic() {
        return "COMIC".equals(category);
    }

    public String displayAuthor() {
        return author == null || author.trim().isEmpty() ? "" : author.trim();
    }

    public String displayFormat() {
        return format == null ? "" : format.toUpperCase();
    }

    public static String extOf(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        if (i < 0 || i == name.length() - 1) return "";
        return name.substring(i + 1).toLowerCase(java.util.Locale.ROOT);
    }

    public Book copy() {
        Book b = new Book();
        b.uri = uri;
        b.name = name;
        b.author = author;
        b.format = format;
        b.coverPath = coverPath;
        b.category = category;
        b.favorite = favorite;
        b.lastReadTime = lastReadTime;
        b.totalReadTime = totalReadTime;
        b.addedTime = addedTime;
        b.size = size;
        b.lastPage = lastPage;
        b.lastOffset = lastOffset;
        b.lastChapter = lastChapter;
        b.progress = progress;
        b.bookmarks = bookmarks;
        return b;
    }
}
