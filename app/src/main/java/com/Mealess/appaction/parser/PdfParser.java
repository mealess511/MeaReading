package com.Mealess.appaction.parser;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import com.Mealess.appaction.core.Io;

import java.io.File;

/** PDF page renderer (Android 5.0+ PdfRenderer). */
public class PdfParser extends AbstractParser {

    private final PdfRenderer renderer;
    private final ParcelFileDescriptor pfd;
    private final int count;

    public PdfParser(File file, int pageChars) throws Exception {
        pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        renderer = new PdfRenderer(pfd);
        count = renderer.getPageCount();
    }

    @Override public boolean isImage() { return true; }
    @Override public int pageCount() { return count; }
    @Override public String format() { return "pdf"; }

    @Override
    public Bitmap image(int index, int width, int height) {
        if (index < 0 || index >= count) return null;
        PdfRenderer.Page page = null;
        Bitmap bmp = null;
        try {
            synchronized (renderer) {
                page = renderer.openPage(index);
                int pw = page.getWidth();
                int ph = page.getHeight();
                if (pw <= 0 || ph <= 0) return null;
                int targetW = Math.max(320, Math.min(width, 1600));
                float ratio = (float) targetW / pw;
                int targetH = Math.max(1, (int) (ph * ratio));
                bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            }
            return bmp;
        } catch (Throwable t) {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
            return null;
        } finally {
            if (page != null) {
                try { page.close(); } catch (Throwable ignored) {}
            }
        }
    }

    @Override
    public void close() {
        try { renderer.close(); } catch (Throwable ignored) {}
        Io.closeQuietly(pfd);
    }
}
