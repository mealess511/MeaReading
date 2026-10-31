package com.Mealess.appaction.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads zip archives that are not comics: html/xhtml bundles and fb2.zip containers.
 */
public final class ZipContent {

    private ZipContent() {}

    public static List<String> htmlEntries(ZipFile zip) {
        List<String> names = new ArrayList<>();
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            String l = e.getName().toLowerCase(Locale.ROOT);
            if (l.endsWith(".html") || l.endsWith(".htm") || l.endsWith(".xhtml") || l.endsWith(".txt")) {
                names.add(e.getName());
            }
        }
        Collections.sort(names, NaturalOrder.INSTANCE);
        return names;
    }

    public static List<String> entriesWithExt(ZipFile zip, String ext) {
        List<String> names = new ArrayList<>();
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            if (e.getName().toLowerCase(Locale.ROOT).endsWith("." + ext)) names.add(e.getName());
        }
        Collections.sort(names, NaturalOrder.INSTANCE);
        return names;
    }

    /** Concatenate every html/txt entry into one plain text document. */
    public static String readText(ZipFile zip) {
        StringBuilder sb = new StringBuilder();
        for (String name : htmlEntries(zip)) {
            InputStream in = null;
            try {
                ZipEntry e = zip.getEntry(name);
                if (e == null) continue;
                in = zip.getInputStream(e);
                byte[] data = Io.readAll(in);
                String s;
                if (name.toLowerCase(Locale.ROOT).endsWith(".txt")) s = TextCodec.decode(data);
                else s = HtmlText.strip(TextCodec.decodeMarkup(data));
                if (s.trim().isEmpty()) continue;
                sb.append(s).append("\n\n");
            } catch (IOException ignored) {
            } finally {
                Io.closeQuietly(in);
            }
        }
        return sb.toString();
    }

    /** Detect an epub by checking its mimetype entry. */
    public static boolean isEpub(File file) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(file);
            ZipEntry mime = zip.getEntry("mimetype");
            if (mime != null) {
                InputStream in = zip.getInputStream(mime);
                byte[] b = Io.readAll(in, 64);
                Io.closeQuietly(in);
                String s = new String(b, "UTF-8");
                if (s.toLowerCase(Locale.ROOT).contains("epub")) return true;
            }
            // Fall back to "does it contain an OPF"
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory() && e.getName().toLowerCase(Locale.ROOT).endsWith(".opf")) return true;
            }
        } catch (Throwable ignored) {
        } finally {
            Io.closeQuietly(zip);
        }
        return false;
    }

    /** First bytes of a file; used for magic sniffing. */
    public static byte[] head(File f, int n) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            byte[] b = new byte[(int) Math.min(n, raf.length())];
            raf.readFully(b);
            return b;
        } catch (Throwable t) {
            return new byte[0];
        } finally {
            Io.closeQuietly(raf);
        }
    }
}
