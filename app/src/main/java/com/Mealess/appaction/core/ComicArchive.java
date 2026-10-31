package com.Mealess.appaction.core;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
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
 * Uniform access to comic page images stored in a zip/cbz, a rar/cbr or a tar/cbt archive.
 * Entry names are sorted with natural ordering so "10.jpg" comes after "9.jpg".
 */
public interface ComicArchive extends Closeable {

    int size();

    String name(int index);

    InputStream open(int index) throws IOException;

    String kind();

    boolean isImageName(String name);

    // ---------------------------------------------------------------- factory

    String TAG = "ComicArchive";

    static boolean isImageNameStatic(String name) {
        String l = name.toLowerCase(Locale.ROOT);
        if (l.startsWith("__macosx/") || l.contains("/._") || l.endsWith(".db") || l.endsWith("thumbs.db")) return false;
        return l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png")
                || l.endsWith(".webp") || l.endsWith(".gif") || l.endsWith(".bmp")
                || l.endsWith(".jfif") || l.endsWith(".avif");
    }

    static String extOf(String name) {
        String e = Io.ext(name);
        if (e.equals("jpeg") || e.equals("jfif")) return "jpg";
        return e;
    }

    /** Opens any supported image archive. {@code cacheBase} is only used by the RAR reader. */
    static ComicArchive open(File file, String format, File cacheBase) throws IOException {
        String f = format == null ? "" : format.toLowerCase(Locale.ROOT);
        if (f.equals("rar") || f.equals("cbr")) return new RarComicArchive(file, cacheBase);
        if (f.equals("tar") || f.equals("cbt")) return new TarComicArchive(file);
        if (f.equals("7z") || f.equals("cb7")) throw new IOException("暂不支持 7z 漫画包，请转换为 CBZ/CBR");
        return new ZipComicArchive(file);
    }

    // ---------------------------------------------------------------- zip

    final class ZipComicArchive implements ComicArchive {
        private final ZipFile zip;
        private final List<String> names = new ArrayList<>();

        public ZipComicArchive(File file) throws IOException {
            zip = new ZipFile(file);
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                if (isImageNameStatic(e.getName())) names.add(e.getName());
            }
            Collections.sort(names, NaturalOrder.INSTANCE);
        }

        @Override public int size() { return names.size(); }
        @Override public String name(int index) { return names.get(index); }
        @Override public String kind() { return "zip"; }
        @Override public boolean isImageName(String name) { return isImageNameStatic(name); }

        @Override public InputStream open(int index) throws IOException {
            ZipEntry e = zip.getEntry(names.get(index));
            if (e == null) throw new IOException("条目不存在：" + names.get(index));
            return zip.getInputStream(e);
        }

        @Override public void close() { Io.closeQuietly(zip); }
    }

    // ---------------------------------------------------------------- rar

    /**
     * RAR archives (including RAR5) are extracted to a cache directory once, because
     * solid archives cannot be randomly accessed. Subsequent opens reuse the cache.
     */
    final class RarComicArchive implements ComicArchive {
        private final Archive archive;
        private final List<String> displayNames = new ArrayList<>();
        private final List<File> files = new ArrayList<>();
        private final File cacheDir;

        public RarComicArchive(File file, File cacheBase) throws IOException {
            cacheDir = new File(cacheBase, "rar_" + Io.hash(file.getAbsolutePath()) + "_" + file.length());
            try {
                archive = new Archive(file);
            } catch (Throwable t) {
                throw new IOException("无法打开 RAR：" + t.getMessage());
            }
            try {
                if (archive.isEncrypted()) throw new IOException("该 RAR 已加密，无法读取");
            } catch (Throwable ignored) {}

            List<FileHeader> headers = archive.getFileHeaders();
            if (headers == null) headers = new ArrayList<>(0);
            List<FileHeader> images = new ArrayList<>();
            for (FileHeader h : headers) {
                if (h.isDirectory()) continue;
                String n = h.getFileName();
                if (n != null && isImageNameStatic(n.replace('\\', '/'))) images.add(h);
            }
            if (images.isEmpty()) throw new IOException("RAR 中没有图片");

            // display order
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < images.size(); i++) order.add(i);
            final List<FileHeader> imgs = images;
            Collections.sort(order, (a, b) -> NaturalOrder.INSTANCE.compare(
                    imgs.get(a).getFileName().replace('\\', '/'),
                    imgs.get(b).getFileName().replace('\\', '/')));

            File markerOk = new File(cacheDir, ".complete");
            boolean cached = markerOk.exists() && markerOk.length() == images.size();
            if (!cached) {
                Io.deleteTree(cacheDir);
                if (!cacheDir.exists() && !cacheDir.mkdirs()) throw new IOException("无法创建缓存目录");
                // extract in archive order (required for solid archives)
                for (int i = 0; i < images.size(); i++) {
                    FileHeader h = images.get(i);
                    File out = new File(cacheDir, "p" + i + "." + extClash(images, i));
                    if (!out.exists() || out.length() == 0) {
                        FileOutputStream fos = new FileOutputStream(out);
                        try {
                            archive.extractFile(h, fos);
                        } catch (com.github.junrar.exception.RarException re) {
                            throw new IOException("解压失败：" + re.getMessage());
                        } finally {
                            Io.closeQuietly(fos);
                        }
                    }
                }
                try {
                    FileOutputStream fos = new FileOutputStream(markerOk);
                    fos.write(String.valueOf(images.size()).getBytes("UTF-8"));
                    Io.closeQuietly(fos);
                } catch (IOException ignored) {}
            }
            for (int i : order) {
                displayNames.add(images.get(i).getFileName().replace('\\', '/'));
                files.add(new File(cacheDir, "p" + i + "." + extClash(images, i)));
            }
        }

        private static String extClash(List<FileHeader> imgs, int i) {
            return extOf(imgs.get(i).getFileName().replace('\\', '/'));
        }

        @Override public int size() { return files.size(); }
        @Override public String name(int index) { return displayNames.get(index); }
        @Override public String kind() { return "rar"; }
        @Override public boolean isImageName(String name) { return isImageNameStatic(name); }

        @Override public InputStream open(int index) throws IOException {
            return new java.io.FileInputStream(files.get(index));
        }

        @Override public void close() { Io.closeQuietly(archive); }

        public File cacheDir() { return cacheDir; }
    }

    // ---------------------------------------------------------------- tar

    final class TarComicArchive implements ComicArchive {
        private final RandomAccessFile raf;
        private final List<String> names = new ArrayList<>();
        private final List<long[]> spans = new ArrayList<>();  // {offset, length}

        public TarComicArchive(File file) throws IOException {
            raf = new RandomAccessFile(file, "r");
            final List<Object[]> found = new ArrayList<>();
            long len = raf.length();
            long pos = 0;
            byte[] header = new byte[512];
            while (pos + 512 <= len) {
                raf.seek(pos);
                int read = raf.read(header);
                if (read < 512) break;
                boolean allZero = true;
                for (int i = 0; i < 512; i++) {
                    if (header[i] != 0) { allZero = false; break; }
                }
                if (allZero) break;
                String name = cstr(header, 0, 100);
                String prefix = cstr(header, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                long size = octal(header, 124, 12);
                int type = header[156] & 0xFF;
                long dataStart = pos + 512;
                if ((type == 0 || type == '0') && isImageNameStatic(name)) {
                    found.add(new Object[]{name, dataStart, size});
                }
                long blocks = (size + 511) / 512;
                pos = dataStart + blocks * 512;
            }
            found.sort((a, b) -> NaturalOrder.INSTANCE.compare((String) a[0], (String) b[0]));
            for (Object[] o : found) {
                names.add((String) o[0]);
                spans.add(new long[]{(Long) o[1], (Long) o[2]});
            }
        }

        private static String cstr(byte[] b, int off, int len) {
            int end = off;
            while (end < off + len && b[end] != 0) end++;
            return new String(b, off, end - off, TextCodec.UTF8).trim();
        }

        private static long octal(byte[] b, int off, int len) {
            if ((b[off] & 0x80) != 0) {
                // base-256 (GNU) encoding
                long v = b[off] & 0x7F;
                for (int i = 1; i < len; i++) v = (v << 8) | (b[off + i] & 0xFF);
                return v;
            }
            long v = 0;
            for (int i = off; i < off + len; i++) {
                int c = b[i] & 0xFF;
                if (c == 0 || c == ' ') continue;
                if (c < '0' || c > '7') break;
                v = v * 8 + (c - '0');
            }
            return v;
        }

        @Override public int size() { return names.size(); }
        @Override public String name(int index) { return names.get(index); }
        @Override public String kind() { return "tar"; }
        @Override public boolean isImageName(String name) { return isImageNameStatic(name); }

        @Override public InputStream open(int index) throws IOException {
            long[] span = spans.get(index);
            raf.seek(span[0]);
            return new BoundedStream(raf, span[1]);
        }

        @Override public void close() { Io.closeQuietly(raf); }
    }

    /** Read-only view of a byte range inside a RandomAccessFile. */
    final class BoundedStream extends InputStream {
        private final RandomAccessFile raf;
        private long remaining;
        private long markPos;

        public BoundedStream(RandomAccessFile raf, long length) {
            this.raf = raf;
            this.remaining = length;
            try { this.markPos = raf.getFilePointer(); } catch (IOException ignored) {}
        }

        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = raf.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = raf.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }

        @Override public int available() {
            return (int) Math.min(Integer.MAX_VALUE, remaining);
        }
    }
}
