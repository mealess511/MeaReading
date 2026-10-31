package com.Mealess.appaction;

import android.content.Context;
import android.net.Uri;

import com.Mealess.appaction.core.Io;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Copies SAF documents into a private cache file so parsers can seek. */
public final class CacheUtil {

    private CacheUtil() {}

    public static File sourceDir(Context c) {
        File d = new File(c.getCacheDir(), "src");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File sourceFile(Context c, Uri uri, String ext) throws Exception {
        String safeExt = ext == null || ext.isEmpty() ? "bin" : ext.toLowerCase(java.util.Locale.ROOT);
        File dir = sourceDir(c);
        File target = new File(dir, "b_" + Io.hash(uri.toString()) + "." + safeExt);
        if (target.exists() && target.length() > 0) return target;
        File tmp = new File(dir, target.getName() + ".part");
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("无法打开文件");
        try {
            Io.copy(in, tmp);
        } finally {
            Io.closeQuietly(in);
        }
        if (tmp.length() == 0) {
            tmp.delete();
            throw new Exception("文件为空或无法读取");
        }
        if (target.exists()) target.delete();
        if (!tmp.renameTo(target)) {
            Io.copy(new java.io.FileInputStream(tmp), target);
            tmp.delete();
        }
        return target;
    }

    public static File coverDir(Context c) {
        File d = new File(c.getFilesDir(), "covers");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Removes temporary book copies and extraction caches. */
    public static long clearCache(Context c) {
        long before = Io.dirSize(c.getCacheDir());
        File[] kids = c.getCacheDir().listFiles();
        if (kids != null) {
            for (File f : kids) Io.deleteTree(f);
        }
        return Math.max(0, before - Io.dirSize(c.getCacheDir()));
    }

    public static long cacheSize(Context c) {
        return Io.dirSize(c.getCacheDir());
    }

    static void writeQuietly(File f, byte[] data) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(data);
        } catch (Exception ignored) {
        } finally {
            Io.closeQuietly(out);
        }
    }
}
