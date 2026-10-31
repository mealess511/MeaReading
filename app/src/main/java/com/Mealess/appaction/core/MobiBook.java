package com.Mealess.appaction.core;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reader for MOBI / PRC / AZW / AZW3 (PalmDoc container).
 *
 * Supports uncompressed, PalmDOC (LZ77) and HUFF/CDIC compressed text, strips the
 * per-record trailing entries and decodes the embedded (X)HTML into plain text.
 * Cover thumbnails and author/title metadata are read from the EXTH block.
 */
public final class MobiBook implements Closeable {

    private final RandomAccessFile raf;
    private final long[] offsets;
    private final int recordCount;

    private String text = "";
    private String title = "";
    private String author = "";
    private byte[] cover;

    public MobiBook(File f) throws IOException {
        raf = new RandomAccessFile(f, "r");
        boolean ok = false;
        try {
            long len = raf.length();
            if (len < 100) throw new IOException("文件过小，不是有效的 MOBI");
            byte[] head = readAt(0, 78);
            recordCount = u16(head, 76);
            if (recordCount <= 0 || recordCount > 100000) throw new IOException("MOBI 记录数异常");
            byte[] rt = readAt(78, recordCount * 8);
            offsets = new long[recordCount + 1];
            for (int i = 0; i < recordCount; i++) offsets[i] = u32(rt, i * 8);
            offsets[recordCount] = len;
            for (int i = 0; i < recordCount; i++) {
                if (offsets[i] < 0 || offsets[i] > len) throw new IOException("MOBI 记录表损坏");
                if (offsets[i + 1] < offsets[i]) offsets[i + 1] = offsets[i];
            }
            byte[] r0 = readRecord(0);
            parse(r0);
            ok = true;
        } finally {
            if (!ok) Io.closeQuietly(raf);
        }
    }

    // ------------------------------------------------------------ low level

    private byte[] readAt(long pos, int n) throws IOException {
        if (n <= 0) return new byte[0];
        raf.seek(pos);
        byte[] b = new byte[n];
        int read = 0;
        while (read < n) {
            int r = raf.read(b, read, n - read);
            if (r < 0) break;
            read += r;
        }
        return read == n ? b : Arrays.copyOf(b, read);
    }

    private byte[] readRecord(int i) throws IOException {
        if (i < 0 || i >= recordCount) return new byte[0];
        long start = offsets[i];
        long end = offsets[i + 1];
        long size = end - start;
        if (size <= 0 || size > Integer.MAX_VALUE) return new byte[0];
        return readAt(start, (int) size);
    }

    private static int u16(byte[] b, int off) {
        if (off + 2 > b.length) return 0;
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int u32(byte[] b, int off) {
        if (off + 4 > b.length) return 0;
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static long u32l(byte[] b, int off) {
        return u32(b, off) & 0xFFFFFFFFL;
    }

    // ------------------------------------------------------------ header

    private void parse(byte[] r0) throws IOException {
        if (r0.length < 16) throw new IOException("MOBI 头部损坏");
        int compression = u16(r0, 0);
        int textLen = u32(r0, 4);
        int textRecCount = u16(r0, 8);
        int encryption = u16(r0, 12);

        boolean isMobi = r0.length >= 20 && r0[16] == 'M' && r0[17] == 'O' && r0[18] == 'B' && r0[19] == 'I';
        int encoding = 1252;
        int huffOffset = -1;
        int huffCount = 0;
        int firstImage = -1;
        int exthFlags = 0;
        int extraFlags = 0;

        if (isMobi) {
            int hdrLen = u32(r0, 20);
            encoding = u32(r0, 28);
            firstImage = u32(r0, 108);
            huffOffset = u32(r0, 112);
            huffCount = u32(r0, 116);
            exthFlags = u32(r0, 128);
            if (r0.length > 244) extraFlags = u16(r0, 242);
            if (firstImage == -1 || firstImage < 0) firstImage = -1;

            // full name (fallback title)
            int nameOff = u32(r0, 84);
            int nameLen = u32(r0, 88);
            if (nameOff > 0 && nameLen > 0 && nameOff + nameLen <= r0.length) {
                String s = new String(r0, nameOff, Math.min(nameLen, 512), TextCodec.UTF8);
                title = s.trim();
            }
            if (exthFlags != 0 && (exthFlags & 0x40) != 0 && hdrLen > 0) {
                parseExth(r0, 16 + hdrLen);
            }
        }

        if (encryption != 0) {
            throw new IOException("该 MOBI 文件带有 DRM 加密，无法读取");
        }

        int textStart = 1;
        int count = textRecCount > 0 ? textRecCount : recordCount - 1;
        count = Math.min(count, recordCount - textStart);
        if (count <= 0) throw new IOException("MOBI 中没有正文记录");

        ByteArrayOutputStream html = new ByteArrayOutputStream(Math.max(textLen, 65536));
        HuffReader huff = null;
        if (compression == 17480) {
            if (huffOffset < 0 || huffOffset >= recordCount) throw new IOException("MOBI HUFF 表缺失");
            List<byte[]> huffs = new ArrayList<>();
            for (int i = 0; i < Math.max(1, huffCount); i++) {
                byte[] h = readRecord(huffOffset + i);
                if (h.length > 0) huffs.add(h);
            }
            if (huffs.isEmpty()) throw new IOException("MOBI HUFF 表为空");
            huff = new HuffReader(huffs);
        } else if (compression != 1 && compression != 2) {
            throw new IOException("不支持的 MOBI 压缩方式：" + compression);
        }

        for (int i = 0; i < count; i++) {
            byte[] rec = readRecord(textStart + i);
            if (rec.length == 0) continue;
            int trail = trailingSize(rec, extraFlags);
            if (trail > 0 && trail <= rec.length) rec = Arrays.copyOf(rec, rec.length - trail);
            byte[] plain;
            if (compression == 17480) plain = huff.unpack(rec);
            else if (compression == 2) plain = palmdoc(rec);
            else plain = rec;
            html.write(plain, 0, plain.length);
        }

        if (textLen > 4096 && html.size() < textLen * 0.6) {
            throw new IOException("MOBI 正文解压不完整（压缩格式不受支持或文件损坏）");
        }

        String enc = encoding == 65001 ? "UTF-8" : (encoding == 1252 ? "windows-1252" : null);
        String raw;
        byte[] bytes = html.toByteArray();
        if (enc != null) {
            try {
                raw = new String(bytes, enc);
            } catch (Throwable t) {
                raw = new String(bytes, TextCodec.UTF8);
            }
        } else {
            raw = TextCodec.decode(bytes);
        }
        raw = raw.replace("\u0000", "");
        String marked = raw
                .replaceAll("(?i)<mbp:pagebreak[^>]*>", "\n\n")
                .replaceAll("(?i)<\\?xml[^>]*>", "")
                .replaceAll("(?i)<!DOCTYPE[^>]*>", "");
        text = HtmlText.strip(marked);
        if (text.trim().isEmpty()) {
            // Not markup at all - keep the raw text
            text = TextEngine.normalize(raw);
        }
        if (title == null || title.trim().isEmpty()) title = "MOBI 书籍";

        if (cover == null && firstImage >= 0 && firstImage < recordCount) {
            // Use the cover EXTH offset when available, else the first image record
            int idx = coverRecordIndex >= 0 ? firstImage + coverRecordIndex : firstImage;
            for (int probe = 0; probe < 3 && idx + probe < recordCount; probe++) {
                byte[] img = readRecord(idx + probe);
                if (isImage(img)) { cover = img; break; }
            }
        }
    }

    private int coverRecordIndex = -1;

    private void parseExth(byte[] r0, int start) {
        if (start <= 0 || start + 12 > r0.length) return;
        if (r0[start] != 'E' || r0[start + 1] != 'X' || r0[start + 2] != 'T' || r0[start + 3] != 'H') return;
        int count = u32(r0, start + 8);
        int pos = start + 12;
        for (int i = 0; i < count && i < 1000; i++) {
            if (pos + 8 > r0.length) break;
            int type = u32(r0, pos);
            int len = u32(r0, pos + 4);
            if (len < 8 || pos + len > r0.length) break;
            int dataOff = pos + 8;
            int dataLen = len - 8;
            switch (type) {
                case 100:
                    if (author.isEmpty()) author = new String(r0, dataOff, dataLen, TextCodec.UTF8).trim();
                    break;
                case 503:
                    title = new String(r0, dataOff, dataLen, TextCodec.UTF8).trim();
                    break;
                case 201:
                    if (dataLen >= 4) coverRecordIndex = u32(r0, dataOff);
                    break;
                default:
                    break;
            }
            pos += len;
        }
    }

    private static boolean isImage(byte[] b) {
        if (b == null || b.length < 8) return false;
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return true;
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return true;
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return true;
        return b[0] == 'B' && b[1] == 'M';
    }

    // ------------------------------------------------------------ trailing entries

    static int trailingSize(byte[] data, int extraFlags) {
        if (extraFlags == 0 || data.length == 0) return 0;
        int num = 0;
        int size = data.length;
        int flags = extraFlags >>> 1;
        while (flags != 0) {
            if ((flags & 1) != 0) {
                num += trailingEntrySize(data, size - num);
                if (num > size) return 0;
            }
            flags >>>= 1;
        }
        if ((extraFlags & 1) != 0) {
            int off = size - num - 1;
            if (off >= 0 && off < size) num += (data[off] & 0x3) + 1;
            else num += 1;
        }
        return num > size ? 0 : num;
    }

    private static int trailingEntrySize(byte[] data, int end) {
        int bitpos = 0;
        int result = 0;
        int psize = end;
        while (true) {
            if (psize - 1 < 0 || (psize - 1) >= data.length) return result;
            int v = data[psize - 1] & 0xFF;
            result |= (v & 0x7F) << bitpos;
            bitpos += 7;
            psize--;
            if ((v & 0x80) != 0 || bitpos >= 28 || psize == 0) return result;
        }
    }

    // ------------------------------------------------------------ palmdoc

    static byte[] palmdoc(byte[] in) {
        byte[] out = new byte[Math.max(64, in.length * 4)];
        int op = 0;
        int i = 0;
        while (i < in.length) {
            int c = in[i++] & 0xFF;
            if (c == 0) {
                if (op + 1 > out.length) out = grow(out, op + 1);
                out[op++] = 0;
            } else if (c <= 8) {
                if (i + c > in.length) c = in.length - i;
                if (op + c > out.length) out = grow(out, op + c);
                System.arraycopy(in, i, out, op, c);
                i += c;
                op += c;
            } else if (c <= 0x7F) {
                if (op + 1 > out.length) out = grow(out, op + 1);
                out[op++] = (byte) c;
            } else if (c <= 0xBF) {
                if (i >= in.length) break;
                int c2 = in[i++] & 0xFF;
                int n = (c << 8) | c2;
                int dist = (n >>> 3) & 0x7FF;
                int len = (n & 7) + 3;
                if (dist <= 0 || dist > op) break;
                if (op + len > out.length) out = grow(out, op + len);
                for (int k = 0; k < len; k++) {
                    out[op] = out[op - dist];
                    op++;
                }
            } else {
                if (op + 2 > out.length) out = grow(out, op + 2);
                out[op++] = ' ';
                out[op++] = (byte) (c ^ 0x80);
            }
        }
        return Arrays.copyOf(out, op);
    }

    private static byte[] grow(byte[] b, int need) {
        int n = Math.max(need, b.length + (b.length >> 1) + 64);
        return Arrays.copyOf(b, n);
    }

    // ------------------------------------------------------------ HUFF / CDIC

    static final class HuffReader {
        private static final class D1 {
            int codelen;
            boolean term;
            long maxcode;
        }
        private final D1[] dict1 = new D1[256];
        private final long[] mincode = new long[33];
        private final long[] maxcode = new long[33];
        private final List<byte[]> raw = new ArrayList<>();
        private final List<byte[]> expanded = new ArrayList<>();

        HuffReader(List<byte[]> records) throws IOException {
            if (records.isEmpty()) throw new IOException("HUFF 记录为空");
            byte[] huff = records.get(0);
            if (huff.length < 24 || huff[0] != 'H' || huff[1] != 'U' || huff[2] != 'F' || huff[3] != 'F') {
                throw new IOException("HUFF 头损坏");
            }
            int off1 = u32(huff, 8);
            int off2 = u32(huff, 12);
            if (off1 < 0 || off1 + 256 * 4 > huff.length) throw new IOException("HUFF 表越界");
            for (int i = 0; i < 256; i++) {
                long v = u32l(huff, off1 + i * 4);
                D1 e = new D1();
                e.codelen = (int) (v & 0x1F);
                e.term = (v & 0x80) != 0;
                long mc = v >>> 8;
                if (e.codelen <= 0) e.codelen = 1;
                e.maxcode = ((mc + 1) << (32 - e.codelen)) - 1;
                dict1[i] = e;
            }
            if (off2 < 0 || off2 + 64 * 4 > huff.length) throw new IOException("HUFF 字典越界");
            for (int codelen = 1; codelen <= 32; codelen++) {
                long min = u32l(huff, off2 + (codelen - 1) * 8);
                long max = u32l(huff, off2 + (codelen - 1) * 8 + 4);
                mincode[codelen] = min << (32 - codelen);
                maxcode[codelen] = ((max + 1) << (32 - codelen)) - 1;
            }
            for (int i = 1; i < records.size(); i++) loadCdic(records.get(i));
            if (raw.isEmpty()) throw new IOException("HUFF 缺少 CDIC 字典");
        }

        private void loadCdic(byte[] cdic) {
            if (cdic.length < 16 || cdic[0] != 'C' || cdic[1] != 'D' || cdic[2] != 'I' || cdic[3] != 'C') return;
            long phrases = u32l(cdic, 8);
            int bits = u32(cdic, 12);
            if (bits < 0 || bits > 24) return;
            long avail = phrases - raw.size();
            long n = Math.min(1L << bits, avail);
            if (n <= 0) return;
            for (int i = 0; i < n; i++) {
                int entry = 16 + i * 2;
                if (entry + 2 > cdic.length) break;
                int off = u16(cdic, entry);
                int pos = 16 + off;
                if (pos + 2 > cdic.length) break;
                int blen = u16(cdic, pos);
                int len = blen & 0x7FFF;
                int start = pos + 2;
                if (start + len > cdic.length) {
                    len = Math.max(0, cdic.length - start);
                }
                raw.add(Arrays.copyOfRange(cdic, start, start + len));
                expanded.add(null);
            }
        }

        byte[] unpack(byte[] data) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length * 3));
            try {
                unpack(data, out, 0);
            } catch (Throwable ignored) {
            }
            return out.toByteArray();
        }

        private void unpack(byte[] data, ByteArrayOutputStream out, int depth) {
            if (data == null || data.length == 0 || depth > 32) return;
            int bitsleft = data.length * 8;
            byte[] buf = Arrays.copyOf(data, data.length + 8);
            int pos = 0;
            long x = readLong(buf, 0);
            int n = 32;
            while (true) {
                if (n <= 0) {
                    pos += 4;
                    if (pos + 8 > buf.length) break;
                    x = readLong(buf, pos);
                    n += 32;
                }
                long code = (x >>> n) & 0xFFFFFFFFL;
                D1 e = dict1[(int) ((code >>> 24) & 0xFF)];
                int codelen = e.codelen;
                long mc = e.maxcode;
                if (!e.term) {
                    while (codelen < 32 && code < mincode[codelen]) codelen++;
                    mc = maxcode[codelen];
                }
                n -= codelen;
                bitsleft -= codelen;
                if (bitsleft < 0) break;
                int shift = 32 - codelen;
                long idx = shift >= 32 ? 0 : ((mc - code) >>> shift);
                int r = (int) idx;
                if (r < 0 || r >= raw.size()) break;
                byte[] res = expanded.get(r);
                if (res == null) {
                    ByteArrayOutputStream tmp = new ByteArrayOutputStream(64);
                    unpack(raw.get(r), tmp, depth + 1);
                    res = tmp.toByteArray();
                    expanded.set(r, res);
                }
                out.write(res, 0, res.length);
            }
        }

        private static long readLong(byte[] b, int off) {
            long v = 0;
            for (int i = 0; i < 8; i++) {
                int idx = off + i;
                v = (v << 8) | (idx < b.length ? (b[idx] & 0xFF) : 0);
            }
            return v;
        }
    }

    // ------------------------------------------------------------ accessors

    public String text() {
        return text;
    }

    public String title() {
        return title;
    }

    public String author() {
        return author;
    }

    public byte[] cover() {
        return cover;
    }

    public int recordCount() {
        return recordCount;
    }

    @Override
    public void close() {
        Io.closeQuietly(raf);
    }
}
