package com.Mealess.appaction.core;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A complete, tolerant EPUB reader.
 *
 * - resolves META-INF/container.xml -> OPF
 * - reads the manifest + spine (reading order) with a lenient attribute scanner
 * - builds chapter titles from the NCX, the EPUB3 nav document or the HTML itself
 * - serves resources straight out of the zip (no extraction), see {@link #open(String)}
 */
public final class EpubBook implements Closeable {

    public static final class Chapter {
        public final String href;    // normalised path inside the zip
        public final String title;
        public Chapter(String href, String title) {
            this.href = href;
            this.title = title;
        }
        @Override public String toString() { return title; }
    }

    private static final Pattern TAG = Pattern.compile("(?is)<(?:\\w+:)?(item|itemref|meta|spine|rootfile|reference|navPoint|content|text|a|h[1-6])\\b([^>]*)>");
    private static final Pattern ATTR = Pattern.compile("([\\w:.-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");
    private static final Pattern LINK = Pattern.compile("(?is)<a\\b([^>]*)>(.*?)</a>");
    private static final Pattern META_CHARSET = Pattern.compile("(?i)<meta[^>]+charset\\s*=\\s*[\"']?([a-zA-Z0-9_\\-]+)");

    private final File file;
    private final ZipFile zip;
    private final Map<String, ZipEntry> entries = new HashMap<>();  // exact + normalised keys
    private final Map<String, String> lowerToNorm = new HashMap<>();
    private final List<Chapter> chapters = new ArrayList<>();
    private String opfPath;
    private String coverPath;
    private String title = "";
    private String author = "";

    public EpubBook(File file) throws IOException {
        this.file = file;
        this.zip = new ZipFile(file);
        try {
            indexEntries();
            String container = readIfPresent("META-INF/container.xml");
            if (container != null) {
                opfPath = firstAttrValue(container, "full-path");
            }
            if (opfPath == null || !hasEntry(opfPath)) {
                opfPath = findFirstEntry("opf");
            }
            if (opfPath == null) throw new IOException("不是有效的 EPUB：缺少 OPF");
            opfPath = normalize(opfPath);
            parseOpf();
            if (chapters.isEmpty()) {
                // Last resort: every xhtml file in natural order
                List<String> htmls = new ArrayList<>();
                for (String name : entries.keySet()) {
                    String l = name.toLowerCase(Locale.ROOT);
                    if (l.endsWith(".xhtml") || l.endsWith(".html") || l.endsWith(".htm")) htmls.add(name);
                }
                Collections.sort(htmls, NaturalOrder.INSTANCE);
                for (String h : htmls) {
                    String t = HtmlText.title(decodeEntry(h));
                    if (t.isEmpty()) t = Io.stripExt(baseName(h));
                    chapters.add(new Chapter(h, t));
                }
            }
        } catch (IOException e) {
            Io.closeQuietly(zip);
            throw e;
        } catch (RuntimeException e) {
            Io.closeQuietly(zip);
            throw new IOException(e);
        }
    }

    // ------------------------------------------------------------------ entries

    private static String stripHash(String s) {
        int i = s.indexOf('#');
        return i >= 0 ? s.substring(0, i) : s;
    }

    private void indexEntries() {
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            String name = normalize(e.getName());
            if (name == null || name.isEmpty()) continue;
            entries.put(name, e);
            lowerToNorm.put(name.toLowerCase(Locale.ROOT), name);
        }
    }

    private String findFirstEntry(String ext) {
        for (String name : entries.keySet()) {
            if (name.toLowerCase(Locale.ROOT).endsWith("." + ext)) return name;
        }
        return null;
    }

    public boolean hasEntry(String path) {
        return entries.containsKey(normalize(path));
    }

    public InputStream open(String path) {
        String key = normalize(path);
        if (key == null) return null;
        ZipEntry e = entries.get(key);
        if (e == null) {
            String alt = lowerToNorm.get(key.toLowerCase(Locale.ROOT));
            if (alt != null) e = entries.get(alt);
        }
        if (e == null) {
            String alt = lowerToNorm.get(percentDecode(key).toLowerCase(Locale.ROOT));
            if (alt != null) e = entries.get(alt);
        }
        if (e == null) return null;
        try {
            return zip.getInputStream(e);
        } catch (IOException ex) {
            return null;
        }
    }

    public byte[] readEntry(String path) {
        InputStream in = open(path);
        if (in == null) return null;
        try {
            return Io.readAll(in);
        } catch (IOException e) {
            return null;
        } finally {
            Io.closeQuietly(in);
        }
    }

    private String decodeEntry(String path) {
        byte[] data = readEntry(path);
        if (data == null) return "";
        return TextCodec.decodeMarkup(data);
    }

    private String readIfPresent(String path) {
        byte[] b = readEntry(path);
        return b == null ? null : TextCodec.decode(b);
    }

    // ------------------------------------------------------------------ paths

    public static String normalize(String path) {
        if (path == null) return null;
        String p = path.replace('\\', '/').trim();
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        int h = p.indexOf('#');
        if (h >= 0) p = p.substring(0, h);
        p = percentDecode(p);
        while (p.startsWith("./")) p = p.substring(2);
        if (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty()) return p;
        StringBuilder sb = new StringBuilder(p.length());
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                int idx = sb.lastIndexOf("/");
                if (idx >= 0) sb.setLength(idx);
                else sb.setLength(0);
            } else {
                if (sb.length() > 0) sb.append('/');
                sb.append(seg);
            }
        }
        return sb.toString();
    }

    /** Resolve {@code href} against the directory of {@code baseFile} (both zip-internal paths). */
    public static String resolve(String baseFile, String href) {
        if (href == null) return null;
        String h = href.trim().replace('\\', '/');
        int hash = h.indexOf('#');
        if (hash >= 0) h = h.substring(0, hash);
        if (h.startsWith("//") || h.contains("://")) return null;
        if (h.startsWith("/")) return normalize(h);
        String dir = baseFile == null ? "" : baseFile;
        int slash = dir.lastIndexOf('/');
        dir = slash >= 0 ? dir.substring(0, slash) : "";
        if (dir.isEmpty()) return normalize(h);
        return normalize(dir + "/" + h);
    }

    public static String percentDecode(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    sb.append((char) (hi * 16 + lo));
                    i += 2;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Percent-encode a zip path so it can be used as a WebView base URL. */
    public static String encodePath(String path) {
        StringBuilder sb = new StringBuilder(path.length() + 16);
        byte[] bytes;
        bytes = path.getBytes(TextCodec.UTF8);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '/' || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    private static String baseName(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    // ------------------------------------------------------------------ opf

    private static String firstAttrValue(String xml, String attr) {
        Matcher m = Pattern.compile("(?i)" + attr + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").matcher(xml);
        if (m.find()) return m.group(2) != null ? m.group(2) : m.group(3);
        return null;
    }

    private static Map<String, String> attrs(String s) {
        Map<String, String> map = new HashMap<>();
        Matcher m = ATTR.matcher(s);
        while (m.find()) {
            String v = m.group(3) != null ? m.group(3) : m.group(4);
            map.put(m.group(1).toLowerCase(Locale.ROOT), HtmlText.decodeEntities(v == null ? "" : v));
        }
        return map;
    }

    private void parseOpf() {
        String opf = readIfPresent(opfPath);
        if (opf == null) return;
        String opfDir = opfPath;
        int slash = opfDir.lastIndexOf('/');
        opfDir = slash >= 0 ? opfDir.substring(0, slash) : "";

        Map<String, String> idToHref = new LinkedHashMap<>();
        Map<String, String> idToType = new HashMap<>();
        Map<String, String> properties = new HashMap<>();
        List<String> spine = new ArrayList<>();

        Matcher m = TAG.matcher(opf);
        while (m.find()) {
            String tag = m.group(1).toLowerCase(Locale.ROOT);
            Map<String, String> a = attrs(m.group(2));
            if (tag.equals("item")) {
                String id = a.get("id");
                String href = a.get("href");
                if (id != null && href != null) {
                    String path = opfDir.isEmpty() ? normalize(href) : resolve(opfPath, href);
                    if (path != null) {
                        idToHref.put(id, path);
                        idToType.put(id, a.get("media-type"));
                        String prop = a.get("properties");
                        if (prop != null) properties.put(id, prop.toLowerCase(Locale.ROOT));
                    }
                }
            } else if (tag.equals("itemref")) {
                String idref = a.get("idref");
                String linear = a.get("linear");
                if (idref != null && (linear == null || !linear.equalsIgnoreCase("no"))) spine.add(idref);
            } else if (tag.equals("meta")) {
                String name = a.get("name");
                String content = a.get("content");
                if (name != null && content != null && name.equalsIgnoreCase("cover") && coverPath == null) {
                    String path = idToHref.get(content);
                    if (path != null) coverPath = path;
                }
            } else if (tag.equals("rootfile")) {
                // ignored, handled by container parsing
            }
        }

        // metadata
        Matcher tm = Pattern.compile("(?is)<dc:title\\b[^>]*>(.*?)</dc:title>").matcher(opf);
        if (tm.find()) title = HtmlText.stripInline(tm.group(1));
        Matcher am = Pattern.compile("(?is)<dc:creator\\b[^>]*>(.*?)</dc:creator>").matcher(opf);
        if (am.find()) author = HtmlText.stripInline(am.group(1));

        // cover-image property
        if (coverPath == null) {
            for (Map.Entry<String, String> e : properties.entrySet()) {
                if (e.getValue().contains("cover-image")) {
                    coverPath = idToHref.get(e.getKey());
                    break;
                }
            }
        }

        List<String> spinePaths = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String id : spine) {
            String path = idToHref.get(id);
            if (path == null || !seen.add(path)) continue;
            String type = idToType.get(id);
            if (type != null && !type.contains("html") && !type.contains("xml")) {
                // Still allow xml/html-ish content, skip images/styles/audio
                if (type.startsWith("image/") || type.equals("text/css") || type.startsWith("audio/") || type.startsWith("video/")) continue;
            }
            spinePaths.add(path);
        }
        if (spinePaths.isEmpty()) {
            for (Map.Entry<String, String> e : idToHref.entrySet()) {
                String type = idToType.get(e.getKey());
                if (type != null && (type.contains("html") || type.equals("application/x-dtbook+xml"))) {
                    if (seen.add(e.getValue())) spinePaths.add(e.getValue());
                }
            }
            Collections.sort(spinePaths, NaturalOrder.INSTANCE);
        }

        Map<String, String> tocTitles = parseNavTitles(opf, opfDir, idToHref, properties);

        for (String path : spinePaths) {
            String name = tocTitles.get(path);
            if (isWeakTitle(name)) {
                name = chapterTitleFromHtml(path);
            }
            chapters.add(new Chapter(path, name));
        }
    }

    /** Some NCX files only contain page markers like "{12}" which make a useless TOC. */
    private static boolean isWeakTitle(String t) {
        if (t == null) return true;
        String s = t.trim();
        if (s.length() < 2) return true;
        if (s.matches("^[\\{\\[\\(]?[0-9ivxlcdmIVXLCDM]{1,4}[\\}\\]\\)]?$")) return true;
        return false;
    }

    private String chapterTitleFromHtml(String path) {
        String html = decodeEntry(path);
        String heading = HtmlText.heading(html);
        if (!heading.isEmpty()) return heading;
        String title = HtmlText.title(html);
        if (!title.isEmpty()) return title;
        return Io.stripExt(baseName(path));
    }

    /** Read titles from toc.ncx and/or the EPUB3 nav document. */
    private Map<String, String> parseNavTitles(String opf, String opfDir, Map<String, String> idToHref,
                                               Map<String, String> properties) {
        Map<String, String> byPath = new HashMap<>();
        // 1) EPUB3 nav document
        String navPath = null;
        for (Map.Entry<String, String> e : properties.entrySet()) {
            if (e.getValue().contains("nav")) {
                navPath = idToHref.get(e.getKey());
                if (navPath != null) break;
            }
        }
        if (navPath != null) {
            String navHtml = decodeEntry(navPath);
            if (!navHtml.isEmpty()) {
                Matcher lm = LINK.matcher(navHtml);
                while (lm.find()) {
                    Map<String, String> a = attrs(lm.group(1));
                    String href = a.get("href");
                    if (href == null) continue;
                    String text = HtmlText.stripInline(lm.group(2));
                    if (text.isEmpty()) continue;
                    String path = resolve(navPath, href);
                    if (path != null) byPath.put(path, text);
                }
            }
        }
        // 2) NCX (EPUB2)
        String ncxId = null;
        for (Map.Entry<String, String> e : idToHref.entrySet()) {
            String p = e.getValue();
            if (p != null && p.toLowerCase(Locale.ROOT).endsWith(".ncx")) {
                ncxId = p;
                break;
            }
        }
        if (ncxId == null) {
            String spineId = firstAttrValue(opf, "toc");
            if (spineId != null) ncxId = idToHref.get(spineId);
        }
        if (ncxId != null) {
            String ncx = decodeEntry(ncxId);
            if (!ncx.isEmpty()) {
                Matcher nm = Pattern.compile("(?is)<text\\b[^>]*>(.*?)</text>|<content\\b([^>]*)>").matcher(ncx);
                String lastText = null;
                while (nm.find()) {
                    if (nm.group(1) != null) {
                        lastText = HtmlText.stripInline(nm.group(1));
                    } else if (lastText != null) {
                        Map<String, String> a = attrs(nm.group(2));
                        String src = a.get("src");
                        if (src != null) {
                            String path = resolve(ncxId, src);
                            if (path != null) byPath.put(path, lastText);
                        }
                        lastText = null;
                    }
                }
            }
        }
        return byPath;
    }

    // ------------------------------------------------------------------ accessors

    public List<Chapter> chapters() {
        return chapters;
    }

    public int size() {
        return chapters.size();
    }

    public String html(int index) {
        if (index < 0 || index >= chapters.size()) return "";
        return decodeEntry(chapters.get(index).href);
    }

    public String text(int index) {
        return HtmlText.strip(html(index));
    }

    public String baseUrl(int index) {
        if (index < 0 || index >= chapters.size()) return "https://epub.local/";
        return "https://epub.local/" + encodePath(chapters.get(index).href);
    }

    public String href(int index) {
        return index >= 0 && index < chapters.size() ? chapters.get(index).href : "";
    }

    public String title() {
        return title == null ? "" : title;
    }

    public String author() {
        return author == null ? "" : author;
    }

    public String coverPath() {
        if (coverPath != null && hasEntry(coverPath)) return coverPath;
        // Fallback: an image whose name looks like a cover
        for (String name : entries.keySet()) {
            String l = name.toLowerCase(Locale.ROOT);
            if ((l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp"))
                    && l.contains("cover")) {
                return name;
            }
        }
        return null;
    }

    public String opfPath() {
        return opfPath;
    }

    public String mimeOf(String path) {
        String l = path.toLowerCase(Locale.ROOT);
        if (l.endsWith(".css")) return "text/css";
        if (l.endsWith(".js")) return "application/javascript";
        if (l.endsWith(".jpg") || l.endsWith(".jpeg")) return "image/jpeg";
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".gif")) return "image/gif";
        if (l.endsWith(".webp")) return "image/webp";
        if (l.endsWith(".svg")) return "image/svg+xml";
        if (l.endsWith(".ttf")) return "font/ttf";
        if (l.endsWith(".otf")) return "font/otf";
        if (l.endsWith(".woff")) return "font/woff";
        if (l.endsWith(".woff2")) return "font/woff2";
        if (l.endsWith(".mp3")) return "audio/mpeg";
        if (l.endsWith(".mp4")) return "video/mp4";
        if (l.endsWith(".xhtml") || l.endsWith(".xht")) return "application/xhtml+xml";
        if (l.endsWith(".html") || l.endsWith(".htm")) return "text/html";
        if (l.endsWith(".xml") || l.endsWith(".opf") || l.endsWith(".ncx")) return "text/xml";
        return "application/octet-stream";
    }

    public File file() {
        return file;
    }

    @Override
    public void close() {
        Io.closeQuietly(zip);
    }
}
