package com.Mealess.appaction.core;

import java.nio.charset.Charset;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best effort text decoding for local book files.
 * Handles BOM, UTF-8, UTF-16 and GB18030/GBK/Big5 legacy Chinese encodings.
 */
public final class TextCodec {

    public static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset GB18030 = charsetOrNull("GB18030", "GBK");
    private static final Charset BIG5 = charsetOrNull("Big5");
    private static final Charset WIN1252 = charsetOrNull("windows-1252", "ISO-8859-1");
    private static final Pattern META_CHARSET = Pattern.compile(
            "(?i)<meta[^>]+charset\\s*=\\s*[\"']?([a-zA-Z0-9_\\-]+)");

    private TextCodec() {}

    private static Charset charsetOrNull(String... names) {
        for (String n : names) {
            try {
                if (Charset.isSupported(n)) return Charset.forName(n);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static String decode(byte[] data) {
        return decode(data, 0, data == null ? 0 : data.length);
    }

    public static String decode(byte[] data, int off, int len) {
        if (data == null || len <= 0) return "";
        if (off < 0) off = 0;
        if (off + len > data.length) len = data.length - off;

        Charset cs = detect(data, off, len);
        String s;
        try {
            s = new String(data, off, len, cs);
        } catch (Throwable t) {
            s = new String(data, off, len, UTF8);
        }
        if (s.length() > 0 && s.charAt(0) == '\uFEFF') s = s.substring(1);
        return s.replace("\u0000", "");
    }

    public static Charset detect(byte[] d, int off, int len) {
        if (len >= 3 && (d[off] & 0xFF) == 0xEF && (d[off + 1] & 0xFF) == 0xBB && (d[off + 2] & 0xFF) == 0xBF) {
            return UTF8;
        }
        if (len >= 2) {
            int b0 = d[off] & 0xFF, b1 = d[off + 1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) return charsetOrNull("UTF-16LE", "UTF-16");
            if (b0 == 0xFE && b1 == 0xFF) return charsetOrNull("UTF-16BE", "UTF-16");
        }
        // UTF-16 without BOM: a lot of zero bytes in one of the two lanes.
        int limit = Math.min(len, 4096);
        int zeroEven = 0, zeroOdd = 0, total = 0;
        for (int i = off; i + 1 < off + limit; i += 2) {
            total++;
            if (d[i] == 0) zeroEven++;
            if (d[i + 1] == 0) zeroOdd++;
        }
        if (total > 8) {
            if (zeroOdd > total * 0.6) return charsetOrNull("UTF-16LE", "UTF-16");
            if (zeroEven > total * 0.6) return charsetOrNull("UTF-16BE", "UTF-16");
        }
        if (isValidUtf8(d, off, len)) return UTF8;
        if (GB18030 != null) return GB18030;
        if (BIG5 != null) return BIG5;
        return WIN1252 != null ? WIN1252 : UTF8;
    }

    /** Decode an HTML/XHTML/XML byte blob honouring an in-document charset declaration. */
    public static String decodeMarkup(byte[] data) {
        if (data == null || data.length == 0) return "";
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return decode(data, 3, data.length - 3);
        }
        if (data.length >= 2) {
            int b0 = data[0] & 0xFF, b1 = data[1] & 0xFF;
            if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) return decode(data);
        }
        int headLen = Math.min(data.length, 2048);
        String head = new String(data, 0, headLen, charsetOrNull("ISO-8859-1", "windows-1252"));
        Matcher m = META_CHARSET.matcher(head);
        if (m.find()) {
            String name = m.group(1).toLowerCase(Locale.ROOT);
            if (name.startsWith("gb") || name.equals("gbk") || name.equals("gb2312")) {
                if (GB18030 != null) return new String(data, GB18030);
            } else if (name.contains("big5")) {
                if (BIG5 != null) return new String(data, BIG5);
            } else if (name.contains("utf-16")) {
                return decode(data);
            } else if (name.contains("8859") || name.contains("latin")) {
                return new String(data, WIN1252 != null ? WIN1252 : UTF8);
            }
        }
        return decode(data);
    }

    public static boolean isValidUtf8(byte[] b, int off, int len) {
        int i = off;
        int end = off + len;
        while (i < end) {
            int c = b[i] & 0xFF;
            if (c < 0x80) {
                i++;
            } else if (c >= 0xC2 && c <= 0xDF) {
                if (i + 1 >= end || (b[i + 1] & 0xC0) != 0x80) return false;
                i += 2;
            } else if (c >= 0xE0 && c <= 0xEF) {
                if (i + 2 >= end) return false;
                int c1 = b[i + 1] & 0xFF, c2 = b[i + 2] & 0xFF;
                if ((c1 & 0xC0) != 0x80 || (c2 & 0xC0) != 0x80) return false;
                if (c == 0xE0 && c1 < 0xA0) return false;
                if (c == 0xED && c1 >= 0xA0) return false;
                i += 3;
            } else if (c >= 0xF0 && c <= 0xF4) {
                if (i + 3 >= end) return false;
                int c1 = b[i + 1] & 0xFF, c2 = b[i + 2] & 0xFF, c3 = b[i + 3] & 0xFF;
                if ((c1 & 0xC0) != 0x80 || (c2 & 0xC0) != 0x80 || (c3 & 0xC0) != 0x80) return false;
                if (c == 0xF0 && c1 < 0x90) return false;
                if (c == 0xF4 && c1 >= 0x90) return false;
                i += 4;
            } else {
                return false;
            }
        }
        return true;
    }
}
