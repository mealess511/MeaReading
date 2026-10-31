package com.Mealess.appaction;

import android.content.Context;
import android.content.SharedPreferences;

/** Reader preferences, persisted across sessions. */
public final class Prefs {

    private static final String NAME = "reader_prefs";

    public static final int THEME_WHITE = 0;
    public static final int THEME_SEPIA = 1;
    public static final int THEME_GREEN = 2;
    public static final int THEME_DARK = 3;
    public static final int THEME_NIGHT_BLUE = 4;

    // backgrounds / text colors for the five built-in reading themes
    public static final int[] THEME_BG = {
            0xFFF8F8F6, 0xFFF3E9D2, 0xFFDDE8D8, 0xFF14161A, 0xFF141B26
    };
    public static final int[] THEME_FG = {
            0xFF23262B, 0xFF4A3F33, 0xFF2C382C, 0xFFB4BAC4, 0xFFA9B6C9
    };
    public static final int[] THEME_SECONDARY = {
            0xFF8A9099, 0xFF8A7A66, 0xFF6C7A6C, 0xFF6C7480, 0xFF6C7A8C
    };
    public static final String[] THEME_NAMES = {"纸白", "羊皮", "护眼", "夜间", "夜蓝"};

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static int theme(Context c) {
        int t = sp(c).getInt("theme", THEME_SEPIA);
        return t < 0 || t >= THEME_BG.length ? THEME_SEPIA : t;
    }

    public static void setTheme(Context c, int t) {
        sp(c).edit().putInt("theme", t).apply();
    }

    public static int fontSize(Context c) {
        int v = sp(c).getInt("font", 19);
        return Math.max(12, Math.min(42, v));
    }

    public static void setFontSize(Context c, int v) {
        sp(c).edit().putInt("font", Math.max(12, Math.min(42, v))).apply();
    }

    public static int lineSpacing(Context c) {
        // stored as percent
        int v = sp(c).getInt("ls", 165);
        return Math.max(110, Math.min(240, v));
    }

    public static void setLineSpacing(Context c, int v) {
        sp(c).edit().putInt("ls", Math.max(110, Math.min(240, v))).apply();
    }

    public static int margin(Context c) {
        int v = sp(c).getInt("margin", 22);
        return Math.max(8, Math.min(56, v));
    }

    public static void setMargin(Context c, int v) {
        sp(c).edit().putInt("margin", Math.max(8, Math.min(56, v))).apply();
    }

    public static boolean scrollMode(Context c) {
        return sp(c).getBoolean("scroll", false);
    }

    public static void setScrollMode(Context c, boolean v) {
        sp(c).edit().putBoolean("scroll", v).apply();
    }

    public static boolean volumeKeys(Context c) {
        return sp(c).getBoolean("volkeys", true);
    }

    public static void setVolumeKeys(Context c, boolean v) {
        sp(c).edit().putBoolean("volkeys", v).apply();
    }

    public static boolean reversed(Context c) {
        return sp(c).getBoolean("reverse", false);
    }

    public static void setReversed(Context c, boolean v) {
        sp(c).edit().putBoolean("reverse", v).apply();
    }

    public static boolean pageInfo(Context c) {
        return sp(c).getBoolean("pageinfo", true);
    }

    public static void setPageInfo(Context c, boolean v) {
        sp(c).edit().putBoolean("pageinfo", v).apply();
    }

    public static boolean autoNight(Context c) {
        return sp(c).getBoolean("autonight", true);
    }

    public static void setAutoNight(Context c, boolean v) {
        sp(c).edit().putBoolean("autonight", v).apply();
    }

    public static boolean bold(Context c) {
        return sp(c).getBoolean("bold", false);
    }

    public static void setBold(Context c, boolean v) {
        sp(c).edit().putBoolean("bold", v).apply();
    }

    public static int sortMode(Context c) {
        return sp(c).getInt("sort", 0); // 0 recent, 1 name, 2 added, 3 size
    }

    public static void setSortMode(Context c, int v) {
        sp(c).edit().putInt("sort", v).apply();
    }
}
