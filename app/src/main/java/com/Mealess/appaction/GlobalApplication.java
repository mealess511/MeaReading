package com.Mealess.appaction;

import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.Mealess.appaction.core.Io;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.Thread.UncaughtExceptionHandler;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Application entry point. Installs a crash handler that writes a readable report
 * and offers to show it, which makes field bug reports actionable.
 */
public class GlobalApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(this));
    }

    private static final class CrashHandler implements UncaughtExceptionHandler {
        private final Context app;

        CrashHandler(Context app) {
            this.app = app.getApplicationContext();
        }

        @Override
        public void uncaughtException(Thread thread, Throwable throwable) {
            String log = buildLog(throwable);
            writeLog(log);
            try {
                Intent intent = new Intent(app, CrashActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                intent.putExtra(Intent.EXTRA_TEXT, log);
                app.startActivity(intent);
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            } catch (Throwable t) {
                UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
                if (def != null && def != this) def.uncaughtException(thread, throwable);
            }
        }

        private String buildLog(Throwable throwable) {
            StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            String version = "?";
            try {
                PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
                version = pi.versionName;
            } catch (Throwable ignored) {}
            return "Time   : " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "\n"
                    + "Device : " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                    + "Android: " + Build.VERSION.RELEASE + " (" + Build.VERSION.SDK_INT + ")\n"
                    + "App    : " + version + "\n\n" + sw.toString();
        }

        private void writeLog(String log) {
            try {
                File dir = new File(app.getFilesDir(), "crash");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, "crash_" + System.currentTimeMillis() + ".txt");
                CacheUtil.writeQuietly(f, log.getBytes("UTF-8"));
            } catch (Throwable ignored) {}
        }
    }

    /** Simple, readable crash screen with copy support. */
    public static final class CrashActivity extends Activity {

        private String log = "";

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setTitle("程序遇到问题");
            String extra = getIntent() == null ? null : getIntent().getStringExtra(Intent.EXTRA_TEXT);
            log = TextUtils.isEmpty(extra) ? "无详细信息" : extra;

            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            HorizontalScrollView h = new HorizontalScrollView(this);
            TextView tv = new TextView(this);
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            tv.setPadding(pad, pad, pad, pad);
            tv.setTextSize(12);
            tv.setTextIsSelectable(true);
            tv.setText(log);
            h.addView(tv);
            scroll.addView(h, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            setContentView(scroll);
        }

        @Override
        public boolean onCreateOptionsMenu(Menu menu) {
            menu.add(0, 1, 0, "复制").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            menu.add(0, 2, 1, "重启").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            return true;
        }

        @Override
        public boolean onOptionsItemSelected(MenuItem item) {
            if (item.getItemId() == 1) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("crash", log));
                return true;
            }
            if (item.getItemId() == 2) {
                Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
                if (i != null) startActivity(i);
                finish();
                android.os.Process.killProcess(android.os.Process.myPid());
                return true;
            }
            return super.onOptionsItemSelected(item);
        }
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
    }

    static void log(Object o) {
        Log.d("Mealess", String.valueOf(o));
    }

    static void close(Object o) {
        if (o instanceof java.io.Closeable) Io.closeQuietly((java.io.Closeable) o);
    }
}
