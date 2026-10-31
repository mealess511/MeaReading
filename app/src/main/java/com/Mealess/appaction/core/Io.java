package com.Mealess.appaction.core;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Small IO helpers shared by all parsers. */
public final class Io {

    private Io() {}

    public static byte[] readAll(InputStream in) throws IOException {
        if (in == null) throw new IOException("null stream");
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    public static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[16384];
        int n;
        int total = 0;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            total += n;
            if (max > 0 && total >= max) break;
        }
        return out.toByteArray();
    }

    public static void copy(InputStream in, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        OutputStream out = new FileOutputStream(dest);
        try {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
        } finally {
            closeQuietly(out);
        }
    }

    public static String readText(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            return TextCodec.decode(readAll(in));
        } finally {
            closeQuietly(in);
        }
    }

    public static void closeQuietly(Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Throwable ignored) {}
    }

    public static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public static long dirSize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        if (dir.isFile()) return dir.length();
        long total = 0;
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) total += dirSize(k);
        return total;
    }

    public static String stripExt(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    public static String ext(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        if (i < 0 || i == name.length() - 1) return "";
        return name.substring(i + 1).toLowerCase(java.util.Locale.ROOT);
    }

    public static String safeFileName(String name) {
        if (name == null || name.length() == 0) return "book";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_') sb.append(c);
            else sb.append('_');
        }
        String s = sb.toString();
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    /** Stable hash used for cache directory names. */
    public static String hash(String s) {
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return Long.toHexString(h);
    }
}
