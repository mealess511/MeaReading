package com.Mealess.appaction.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts markup (HTML / XHTML / FB2) into readable plain text. */
public final class HtmlText {

    private static final Pattern SCRIPT = Pattern.compile("(?is)<script\\b[^>]*>.*?</script>");
    private static final Pattern STYLE = Pattern.compile("(?is)<style\\b[^>]*>.*?</style>");
    private static final Pattern HEAD = Pattern.compile("(?is)<head\\b[^>]*>.*?</head>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern SVG = Pattern.compile("(?is)<svg\\b[^>]*>.*?</svg>");
    private static final Pattern BODY = Pattern.compile("(?is)<body\\b[^>]*>(.*)</body>");
    private static final Pattern BLOCK = Pattern.compile(
            "(?i)</?(p|div|br|h[1-6]|li|tr|td|th|section|article|blockquote|pre|ul|ol|table|figure|figcaption|title|part)\\b[^>]*>");
    private static final Pattern TAG = Pattern.compile("<[^>]{0,400}>");
    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9a-fA-F]{1,6}|[a-zA-Z]{2,10});");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern TITLE = Pattern.compile("(?is)<title\\b[^>]*>(.*?)</title>");
    private static final Pattern H = Pattern.compile("(?is)<h([1-3])\\b[^>]*>(.*?)</h\\1>");

    private HtmlText() {}

    public static String strip(String html) {
        if (html == null || html.isEmpty()) return "";
        String s = html;
        s = COMMENT.matcher(s).replaceAll(" ");
        s = SCRIPT.matcher(s).replaceAll(" ");
        s = STYLE.matcher(s).replaceAll(" ");
        s = SVG.matcher(s).replaceAll(" ");
        Matcher body = BODY.matcher(s);
        if (body.find()) s = body.group(1);
        else s = HEAD.matcher(s).replaceAll(" ");
        s = BLOCK.matcher(s).replaceAll("\n");
        s = TAG.matcher(s).replaceAll("");
        s = decodeEntities(s);
        s = s.replace('\u00A0', ' ').replace('\u3000', ' ');
        s = SPACES.matcher(s).replaceAll(" ");
        // trim each line
        StringBuilder out = new StringBuilder(s.length());
        int start = 0;
        int len = s.length();
        while (start <= len) {
            int nl = s.indexOf('\n', start);
            int end = nl < 0 ? len : nl;
            int a = start, b = end;
            while (a < b && s.charAt(a) == ' ') a++;
            while (b > a && s.charAt(b - 1) == ' ') b--;
            if (b > a) out.append(s, a, b);
            out.append('\n');
            if (nl < 0) break;
            start = nl + 1;
        }
        return BLANK_LINES.matcher(out.toString()).replaceAll("\n\n").trim();
    }

    public static String title(String html) {
        if (html == null) return "";
        Matcher m = TITLE.matcher(html);
        if (m.find()) {
            String t = stripInline(m.group(1));
            if (!t.isEmpty()) return t;
        }
        return heading(html);
    }

    /** First h1/h2/h3 text - usually the real chapter heading. */
    public static String heading(String html) {
        if (html == null) return "";
        Matcher m = H.matcher(html);
        while (m.find()) {
            String raw = HtmlText.stripInline(m.group(2));
            if (raw.isEmpty()) continue;
            String best = null;
            for (String line : raw.split("\\n")) {
                String t = SPACES.matcher(line).replaceAll(" ").trim();
                if (t.isEmpty()) continue;
                if (t.length() > 120) t = t.substring(0, 120);
                if (t.matches("(?i)^(chapter|part|section|book|prologue|epilogue)\\b.*")
                        || t.matches("^第[0-9一二三四五六七八九十百千]+[章回节卷部篇].*")) {
                    return t;
                }
                best = t;
            }
            if (best != null && best.length() <= 80) return best;
        }
        return "";
    }

    public static String stripInline(String s) {
        if (s == null) return "";
        String t = TAG.matcher(s).replaceAll("");
        t = decodeEntities(t).replace('\u00A0', ' ');
        return SPACES.matcher(t).replaceAll(" ").trim();
    }

    /** Decode the small set of entities commonly found in ebooks. */
    public static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) return s;
        Matcher m = ENTITY.matcher(s);
        StringBuffer sb = new StringBuffer(s.length());
        while (m.find()) {
            String g = m.group(1);
            String rep;
            if (g.charAt(0) == '#') {
                try {
                    int cp = g.length() > 1 && (g.charAt(1) == 'x' || g.charAt(1) == 'X')
                            ? Integer.parseInt(g.substring(2), 16)
                            : Integer.parseInt(g.substring(1));
                    rep = cp > 0 && cp <= 0x10FFFF ? new String(Character.toChars(cp)) : "";
                } catch (Throwable t) {
                    rep = "";
                }
            } else {
                rep = namedEntity(g);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String namedEntity(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        switch (n) {
            case "amp": return "&";
            case "lt": return "<";
            case "gt": return ">";
            case "quot": return "\"";
            case "apos": return "'";
            case "nbsp": return " ";
            case "mdash": return "—";
            case "ndash": return "–";
            case "hellip": return "…";
            case "ldquo": return "“";
            case "rdquo": return "”";
            case "lsquo": return "‘";
            case "rsquo": return "’";
            case "middot": return "·";
            case "bull": return "•";
            case "copy": return "©";
            case "reg": return "®";
            case "trade": return "™";
            case "deg": return "°";
            case "times": return "×";
            case "divide": return "÷";
            case "laquo": return "«";
            case "raquo": return "»";
            case "emsp": return " ";
            case "ensp": return " ";
            case "thinsp": return " ";
            case "shy": return "";
            default: return "";
        }
    }
}
