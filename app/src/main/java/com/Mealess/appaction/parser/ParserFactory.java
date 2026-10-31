package com.Mealess.appaction.parser;

import android.content.Context;
import android.net.Uri;

import com.Mealess.appaction.Book;
import com.Mealess.appaction.CacheUtil;
import com.Mealess.appaction.core.HtmlText;
import com.Mealess.appaction.core.Io;
import com.Mealess.appaction.core.MobiBook;
import com.Mealess.appaction.core.TextCodec;
import com.Mealess.appaction.core.ZipContent;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Creates the right parser for a book, with the content itself as the source of truth. */
public final class ParserFactory {

    public static final int DEFAULT_PAGE_CHARS = 900;

    private ParserFactory() {}

    public static Parser create(Context c, Book book) throws Exception {
        File local = CacheUtil.sourceFile(c, Uri.parse(book.uri), book.format);
        return create(c, local, book.format, DEFAULT_PAGE_CHARS);
    }

    public static Parser create(Context c, File local, String formatHint, int pageChars) throws Exception {
        String format = formatHint == null ? "" : formatHint.toLowerCase(Locale.ROOT);
        switch (format) {
            case "txt":
            case "md":
            case "log":
                return textOf(local, format, pageChars);
            case "html":
            case "htm":
            case "xhtml":
                return new TextParser(HtmlText.strip(TextCodec.decodeMarkup(Io.readAll(new FileInputStream(local)))),
                        pageChars, "html");
            case "fb2":
                return fb2(local, pageChars);
            case "mobi":
            case "azw":
            case "azw3":
            case "prc":
                return mobi(local, pageChars, format);
            case "epub":
                return new EpubParser(local);
            case "zip":
            case "cbz":
                return zip(c, local, format, pageChars);
            case "rar":
            case "cbr":
                return ComicParser.open(local, "rar", c.getCacheDir());
            case "tar":
            case "cbt":
                return ComicParser.open(local, "tar", c.getCacheDir());
            case "pdf":
                return new PdfParser(local, pageChars);
            default:
                break;
        }
        // Unknown extension: sniff the content
        String detected = com.Mealess.appaction.BookImporter.detect(local, "unknown." + format);
        if (!detected.equals(format)) return create(c, local, detected, pageChars);
        return textOf(local, "txt", pageChars);
    }

    private static TextParser textOf(File local, String format, int pageChars) throws Exception {
        byte[] data = Io.readAll(new FileInputStream(local));
        String text = TextCodec.decode(data);
        return new TextParser(text, pageChars, format);
    }

    private static Parser fb2(File local, int pageChars) throws Exception {
        // fb2 may be a plain xml or a zipped "fb2.zip"/fbz container
        String head = new String(ZipContent.head(local, 4), "ISO-8859-1");
        if (head.startsWith("PK")) {
            ZipFile zip = new ZipFile(local);
            try {
                List<String> inner = ZipContent.entriesWithExt(zip, "fb2");
                if (inner.isEmpty()) throw new Exception("压缩包中没有 FB2 文件");
                ZipEntry e = zip.getEntry(inner.get(0));
                InputStream in = zip.getInputStream(e);
                byte[] data;
                try {
                    data = Io.readAll(in);
                } finally {
                    Io.closeQuietly(in);
                }
                return new TextParser(fb2ToText(data), pageChars, "fb2");
            } finally {
                Io.closeQuietly(zip);
            }
        }
        byte[] data = Io.readAll(new FileInputStream(local));
        return new TextParser(fb2ToText(data), pageChars, "fb2");
    }

    private static String fb2ToText(byte[] data) {
        String xml = TextCodec.decodeMarkup(data);
        xml = xml.replaceAll("(?is)<binary[^>]*>.*?</binary>", " ");
        String text = HtmlText.strip(xml);
        // FB2 puts each paragraph in its own <p>, so the body reads well as plain text.
        return text;
    }

    private static Parser mobi(File local, int pageChars, String format) throws Exception {
        MobiBook mobi = new MobiBook(local);
        try {
            String text = mobi.text();
            if (text == null || text.trim().isEmpty()) throw new Exception("MOBI 中没有可读文本");
            return new TextParser(text, pageChars, format);
        } finally {
            mobi.close();
        }
    }

    private static Parser zip(Context c, File local, String format, int pageChars) throws Exception {
        ZipFile zip = new ZipFile(local);
        try {
            List<String> html = ZipContent.htmlEntries(zip);
            if (!html.isEmpty()) {
                String text = ZipContent.readText(zip);
                if (!text.trim().isEmpty()) return new TextParser(text, pageChars, "zip");
            }
        } finally {
            Io.closeQuietly(zip);
        }
        // If the ZIP holds a single epub, treat it as an epub
        if (ZipContent.isEpub(local)) return new EpubParser(local);
        return ComicParser.open(local, "zip", c.getCacheDir());
    }
}
