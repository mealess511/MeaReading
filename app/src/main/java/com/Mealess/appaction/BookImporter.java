package com.Mealess.appaction;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import com.Mealess.appaction.core.ComicArchive;
import com.Mealess.appaction.core.EpubBook;
import com.Mealess.appaction.core.Io;
import com.Mealess.appaction.core.MobiBook;
import com.Mealess.appaction.core.ZipContent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

/** Formats detection + book import logic. */
public final class BookImporter {

    public static final String[] SUPPORTED = {
            "epub", "mobi", "azw", "azw3", "prc", "txt", "fb2", "pdf",
            "cbz", "cbr", "cbt", "zip", "rar", "tar", "html", "htm", "xhtml", "md"
    };

    private BookImporter() {}

    public static boolean isSupportedName(String name) {
        String ext = Book.extOf(name);
        for (String s : SUPPORTED) if (s.equals(ext)) return true;
        return false;
    }

    public static String queryName(Context c, Uri uri) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(uri, null, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                int idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = cur.getString(idx);
                    if (n != null && !n.trim().isEmpty()) return n;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        String last = uri.getLastPathSegment();
        return last == null ? "未命名" : last;
    }

    public static long querySize(Context c, Uri uri) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(uri, null, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                int idx = cur.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !cur.isNull(idx)) return cur.getLong(idx);
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return 0;
    }

    // -------------------------------------------------------------- detection

    /** Detects the real container format from content, falling back to the file name. */
    public static String detect(File f, String displayName) {
        String byName = Book.extOf(displayName);
        byte[] head = ZipContent.head(f, 1024);
        String ascii = new String(head, java.nio.charset.Charset.forName("ISO-8859-1"));
        String lower = ascii.toLowerCase(Locale.ROOT);

        if (ascii.startsWith("Rar!")) return "rar";
        if (ascii.startsWith("%PDF")) return "pdf";
        if (head.length > 68 && ascii.substring(60, 68).equals("BOOKMOBI")) return "mobi";
        if (head.length > 68 && ascii.substring(60, 68).equals("TEXtREAd")) return "txt";
        if (ascii.startsWith("PK\u0003\u0004") || ascii.startsWith("PK\u0005\u0006")) return detectZip(f, byName);
        if (lower.contains("<fictionbook")) return "fb2";
        if (ascii.startsWith("BZh") || ascii.startsWith("\u001f\u008b")) return byName.isEmpty() ? "txt" : byName;
        if (lower.contains("<html") || lower.contains("<!doctype html") || lower.contains("<?xml")) {
            if (byName.equals("xhtml") || byName.equals("html") || byName.equals("htm") || byName.isEmpty()) return "html";
        }
        // fall back to the extension
        switch (byName) {
            case "epub": case "mobi": case "azw": case "azw3": case "prc": case "fb2":
            case "pdf": case "zip": case "cbz": case "rar": case "cbr": case "tar": case "cbt":
            case "html": case "htm": case "xhtml":
                return byName;
            default:
                return "txt";
        }
    }

    private static String detectZip(File f, String byName) {
        if (ZipContent.isEpub(f)) return "epub";
        ZipFile zip = null;
        try {
            zip = new ZipFile(f);
            List<String> fb2 = ZipContent.entriesWithExt(zip, "fb2");
            if (!fb2.isEmpty()) return "fb2";
            List<String> html = ZipContent.htmlEntries(zip);
            if (!html.isEmpty()) return "zip";
            return "cbz";
        } catch (Throwable t) {
            return byName.equals("epub") ? "epub" : (byName.equals("cbz") ? "cbz" : "zip");
        } finally {
            Io.closeQuietly(zip);
        }
    }

    public static String categoryOf(String format) {
        switch (format) {
            case "cbz": case "cbr": case "cbt": case "rar": case "tar": return "COMIC";
            case "zip": return "COMIC";
            default: return "NOVEL";
        }
    }

    // -------------------------------------------------------------- import

    public static final class ImportResult {
        public Book book;
        public String error;
        public boolean duplicate;
    }

    /** Imports one document. Runs on the calling (background) thread. */
    public static ImportResult importUri(Context c, Uri uri, List<Book> existing, String tabHint) {
        ImportResult r = new ImportResult();
        try {
            String name = queryName(c, uri);
            String ext = Book.extOf(name);
            File local = CacheUtil.sourceFile(c, uri, ext.isEmpty() ? "bin" : ext);
            String format = detect(local, name);
            if (existing != null) {
                for (Book b : existing) {
                    if (b.uri != null && b.uri.equals(uri.toString())) {
                        r.duplicate = true;
                        r.book = b;
                        return r;
                    }
                }
            }
            Book book = new Book(uri.toString(), cleanName(name), format, categoryOf(format));
            book.size = local.length();
            book.addedTime = System.currentTimeMillis();
            book.lastReadTime = 0;
            if ("pdf".equals(format) && "COMIC".equals(tabHint)) book.category = "COMIC";
            if ("zip".equals(format)) {
                // a zip of html pages is a novel bundle, not a comic
                book.category = hasHtml(local) ? "NOVEL" : "COMIC";
            }

            // metadata + cover (best effort)
            try {
                fillMetadata(c, book, local);
            } catch (Throwable ignored) {}
            try {
                Covers.ensure(c, book);
            } catch (Throwable ignored) {}

            r.book = book;
            return r;
        } catch (Throwable t) {
            r.error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            return r;
        }
    }

    private static boolean hasHtml(File f) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(f);
            return !ZipContent.htmlEntries(zip).isEmpty();
        } catch (Throwable t) {
            return false;
        } finally {
            Io.closeQuietly(zip);
        }
    }

    private static void fillMetadata(Context c, Book book, File local) throws Exception {
        String f = book.format;
        if ("epub".equals(f)) {
            EpubBook eb = null;
            try {
                eb = new EpubBook(local);
                if (eb.title() != null && !eb.title().isEmpty()) book.name = eb.title();
                if (eb.author() != null && !eb.author().isEmpty()) book.author = eb.author();
            } finally {
                if (eb != null) eb.close();
            }
        } else if ("mobi".equals(f) || "azw".equals(f) || "azw3".equals(f) || "prc".equals(f)) {
            MobiBook mb = null;
            try {
                mb = new MobiBook(local);
                if (mb.title() != null && !mb.title().isEmpty()) book.name = mb.title();
                if (mb.author() != null && !mb.author().isEmpty()) book.author = mb.author();
            } finally {
                if (mb != null) mb.close();
            }
        }
    }

    private static String cleanName(String name) {
        if (name == null) return "未命名";
        String n = name;
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        n = n.replaceAll("[\\r\\n\\t]", " ").trim();
        if (n.length() > 120) n = n.substring(0, 120);
        return n.isEmpty() ? "未命名" : n;
    }

    // -------------------------------------------------------------- folder import

    /** Recursively imports every supported document inside a document tree. */
    public static int importFolder(Context c, Uri treeUri, List<Book> existing, List<Book> out) {
        int count = 0;
        try {
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            List<Book> seen = new ArrayList<>(existing);
            count = walk(c, treeUri, rootId, seen, out, 0);
        } catch (Throwable ignored) {}
        return count;
    }

    private static int walk(Context c, Uri treeUri, String docId, List<Book> seen, List<Book> out, int depth) {
        if (depth > 4) return 0;
        int count = 0;
        Cursor cur = null;
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
            cur = c.getContentResolver().query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            if (cur == null) return 0;
            List<String[]> dirs = new ArrayList<>();
            List<String[]> files = new ArrayList<>();
            while (cur.moveToNext()) {
                String id = cur.getString(0);
                String name = cur.getString(1);
                String mime = cur.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) dirs.add(new String[]{id, name});
                else files.add(new String[]{id, name});
            }
            for (String[] f : files) {
                if (!isSupportedName(f[1])) continue;
                Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, f[0]);
                ImportResult r = importUri(c, fileUri, seen, "NOVEL");
                if (r.book != null && !r.duplicate && r.error == null) {
                    seen.add(r.book);
                    out.add(r.book);
                    count++;
                }
            }
            for (String[] d : dirs) count += walk(c, treeUri, d[0], seen, out, depth + 1);
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return count;
    }

}
