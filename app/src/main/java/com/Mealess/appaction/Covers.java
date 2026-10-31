package com.Mealess.appaction;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;

import com.Mealess.appaction.core.ComicArchive;
import com.Mealess.appaction.core.EpubBook;
import com.Mealess.appaction.core.Io;
import com.Mealess.appaction.core.MobiBook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

/** Generates and caches cover thumbnails (no network, purely local). */
public final class Covers {

    private static final int COVER_W = 360;
    private static final int COVER_H = 500;

    private Covers() {}

    public static File fileFor(Context c, Book b) {
        return new File(CacheUtil.coverDir(c), Io.hash(b.uri == null ? "?" : b.uri) + ".jpg");
    }

    public static boolean hasCover(Context c, Book b) {
        File f = fileFor(c, b);
        return f.exists() && f.length() > 0;
    }

    public static void delete(Context c, Book b) {
        File f = fileFor(c, b);
        if (f.exists()) f.delete();
    }

    /** Builds the cover if it is missing. Safe to call from a background thread. */
    public static void ensure(Context c, Book b) throws Exception {
        File out = fileFor(c, b);
        if (out.exists() && out.length() > 0) return;
        Bitmap bmp = null;

        // 1) a user supplied cover image
        if (b.coverPath != null && !b.coverPath.isEmpty()) {
            try {
                InputStream in = c.getContentResolver().openInputStream(Uri.parse(b.coverPath));
                bmp = BitmapFactory.decodeStream(in);
                Io.closeQuietly(in);
            } catch (Throwable ignored) {}
        }

        File local = null;
        if (bmp == null) {
            try {
                local = CacheUtil.sourceFile(c, Uri.parse(b.uri), b.format);
            } catch (Throwable ignored) {}

            if (local != null) {
                String f = b.format == null ? "" : b.format.toLowerCase(Locale.ROOT);
                if (f.equals("epub")) bmp = fromEpub(local);
                else if (f.equals("mobi") || f.equals("azw") || f.equals("azw3") || f.equals("prc")) bmp = fromMobi(local, b);
                else if (f.equals("pdf")) bmp = fromPdf(local);
                else if (f.equals("zip") || f.equals("cbz") || f.equals("rar") || f.equals("cbr")
                        || f.equals("tar") || f.equals("cbt")) bmp = fromComic(c, local, f, b);
            }
        }

        if (bmp == null) bmp = placeholder(b.name, b.displayAuthor(), COVER_W, COVER_H);

        Bitmap scaled = scale(bmp, COVER_W, COVER_H, true);
        saveJpeg(scaled, out);
        if (scaled != bmp) bmp.recycle();
        scaled.recycle();
    }

    private static Bitmap fromEpub(File f) {
        EpubBook book = null;
        try {
            book = new EpubBook(f);
            String cover = book.coverPath();
            if (cover == null) return null;
            byte[] data = book.readEntry(cover);
            if (data == null) return null;
            return BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (Throwable t) {
            return null;
        } finally {
            if (book != null) book.close();
        }
    }

    private static Bitmap fromMobi(File f, Book b) {
        MobiBook mobi = null;
        try {
            mobi = new MobiBook(f);
            if (b.author == null || b.author.isEmpty()) b.author = mobi.author();
            if (mobi.title() != null && !mobi.title().isEmpty() && isGenericName(b.name)) b.name = mobi.title();
            byte[] cover = mobi.cover();
            if (cover == null) return null;
            return BitmapFactory.decodeByteArray(cover, 0, cover.length);
        } catch (Throwable t) {
            return null;
        } finally {
            if (mobi != null) mobi.close();
        }
    }

    private static Bitmap fromPdf(File f) {
        android.os.ParcelFileDescriptor pfd = null;
        android.graphics.pdf.PdfRenderer renderer = null;
        android.graphics.pdf.PdfRenderer.Page page = null;
        try {
            pfd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new android.graphics.pdf.PdfRenderer(pfd);
            if (renderer.getPageCount() == 0) return null;
            page = renderer.openPage(0);
            int w = Math.max(200, Math.min(600, page.getWidth()));
            int h = (int) ((float) page.getHeight() / page.getWidth() * w);
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);
            page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bmp;
        } catch (Throwable t) {
            return null;
        } finally {
            if (page != null) try { page.close(); } catch (Throwable ignored) {}
            if (renderer != null) try { renderer.close(); } catch (Throwable ignored) {}
            if (pfd != null) try { pfd.close(); } catch (Throwable ignored) {}
        }
    }

    private static Bitmap fromComic(Context c, File f, String fmt, Book b) {
        ComicArchive archive = null;
        try {
            String format = fmt == null ? "" : fmt.toLowerCase(Locale.ROOT);
            if ((format.equals("rar") || format.equals("cbr") || format.equals("tar") || format.equals("cbt"))
                    && f.length() > 120L * 1024 * 1024) {
                return null; // avoid a long blocking extraction during import
            }
            archive = ComicArchive.open(f, format, c.getCacheDir());
            if (archive.size() == 0) return null;
            InputStream in = archive.open(0);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
            Io.closeQuietly(in);
            int sample = 1;
            while (o.outWidth / (sample * 2) > COVER_W * 2) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            in = archive.open(0);
            Bitmap bmp = BitmapFactory.decodeStream(in, null, o2);
            Io.closeQuietly(in);
            return bmp;
        } catch (Throwable t) {
            return null;
        } finally {
            if (archive != null) try { archive.close(); } catch (Throwable ignored) {}
        }
    }

    private static boolean isGenericName(String name) {
        return name == null || name.trim().isEmpty() || name.startsWith("未命名");
    }

    /** A deterministic gradient cover with the title, used when there is no artwork. */
    public static Bitmap placeholder(String title, String author, int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        String t = title == null || title.isEmpty() ? "未命名" : title;

        int hash = t.hashCode();
        int hue = Math.abs(hash) % 360;
        float[] hsv = {hue, 0.42f, 0.68f};
        int top = Color.HSVToColor(hsv);
        hsv[2] = 0.44f;
        hsv[1] = 0.52f;
        int bottom = Color.HSVToColor(hsv);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, w * 0.4f, h, top, bottom, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, p);

        // subtle vignette
        p.setShader(new LinearGradient(0, h * 0.55f, 0, h, 0x00000000, 0x33000000, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, p);
        p.setShader(null);

        // title, wrapped
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(0xF2FFFFFF);
        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        text.setTextSize(w * 0.115f);
        float pad = w * 0.1f;
        float usable = w - pad * 2;
        String[] lines = wrap(t, text, usable, 5);
        float lineH = text.getTextSize() * 1.28f;
        float y = h * 0.20f + text.getTextSize();
        for (String line : lines) {
            cv.drawText(line, pad, y, text);
            y += lineH;
        }

        // rule + author
        Paint rule = new Paint();
        rule.setColor(0x55FFFFFF);
        rule.setStrokeWidth(Math.max(1f, w * 0.006f));
        cv.drawLine(pad, y + lineH * 0.2f, pad + usable * 0.34f, y + lineH * 0.2f, rule);

        if (author != null && !author.isEmpty()) {
            Paint ap = new Paint(Paint.ANTI_ALIAS_FLAG);
            ap.setColor(0xCCFFFFFF);
            ap.setTextSize(w * 0.062f);
            cv.drawText(ellipsize(author, ap, usable), pad, y + lineH * 0.75f, ap);
        }

        Paint brand = new Paint(Paint.ANTI_ALIAS_FLAG);
        brand.setColor(0x66FFFFFF);
        brand.setTextSize(w * 0.048f);
        brand.setLetterSpacing(0.02f);
        cv.drawText("MeaReading", pad, h - pad * 0.55f, brand);

        return bmp;
    }

    private static String ellipsize(String s, Paint p, float width) {
        if (p.measureText(s) <= width) return s;
        String ell = "…";
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (p.measureText(s.substring(0, mid) + ell) <= width) lo = mid;
            else hi = mid - 1;
        }
        return s.substring(0, Math.max(0, lo)) + ell;
    }

    private static String[] wrap(String text, Paint p, float width, int maxLines) {
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                lines.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(ch);
            if (p.measureText(cur.toString()) > width) {
                // break at the last wide break opportunity
                int cut = cur.length() - 1;
                while (cut > 0 && !isBreak(cur.charAt(cut))) cut--;
                if (cut <= 0) cut = cur.length() - 1;
                lines.add(cur.substring(0, cut).trim());
                String rest = cur.substring(cut).trim();
                cur.setLength(0);
                cur.append(rest);
                if (lines.size() >= maxLines - 1) break;
            }
        }
        if (lines.size() < maxLines && cur.length() > 0) lines.add(cur.toString());
        if (lines.size() > maxLines) {
            lines = new java.util.ArrayList<>(lines.subList(0, maxLines));
        }
        if (lines.size() == maxLines) {
            String last = lines.get(maxLines - 1);
            if (p.measureText(last) > width) last = ellipsize(last, p, width);
            lines.set(maxLines - 1, last);
        }
        return lines.toArray(new String[0]);
    }

    private static boolean isBreak(char c) {
        return c == ' ' || c == '，' || c == '。' || c == '：' || c == '、' || c == '-' || c == '·' || c == '）' || c == '》';
    }

    public static Bitmap scale(Bitmap src, int maxW, int maxH, boolean fillCrop) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        if (w <= 0 || h <= 0) return src;
        float sx = (float) maxW / w;
        float sy = (float) maxH / h;
        float s = fillCrop ? Math.max(sx, sy) : Math.min(sx, sy);
        if (s >= 1f && w <= maxW && h <= maxH) return src;
        Bitmap out = Bitmap.createScaledBitmap(src, Math.max(1, (int) (w * s)), Math.max(1, (int) (h * s)), true);
        if (fillCrop) {
            int x = Math.max(0, (out.getWidth() - maxW) / 2);
            int y = Math.max(0, (out.getHeight() - maxH) / 2);
            int cw = Math.min(maxW, out.getWidth());
            int ch = Math.min(maxH, out.getHeight());
            if (x != 0 || y != 0 || cw != out.getWidth() || ch != out.getHeight()) {
                Bitmap cropped = Bitmap.createBitmap(out, x, y, cw, ch);
                if (cropped != out) out.recycle();
                out = cropped;
            }
        }
        return out;
    }

    private static void saveJpeg(Bitmap bmp, File out) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(out);
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, fos);
            fos.flush();
        } catch (Throwable ignored) {
        } finally {
            Io.closeQuietly(fos);
        }
    }

    public static Bitmap load(File f, int w, int h) {
        if (f == null || !f.exists()) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w * 2 && o.outHeight / (sample * 2) >= h * 2) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void recycleQuietly(Bitmap b) {
        if (b != null && !b.isRecycled()) {
            try { b.recycle(); } catch (Throwable ignored) {}
        }
    }
}
