package com.Mealess.appaction;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.Mealess.appaction.core.Io;
import com.Mealess.appaction.ui.BookAdapter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Bookshelf. */
public class MainActivity extends Activity implements BookAdapter.Listener {

    private static final int REQ_OPEN = 101;
    private static final int REQ_TREE = 102;
    private static final int REQ_COVER = 103;
    private static final int REQ_EXPORT = 104;
    private static final int REQ_IMPORT_JSON = 105;

    private ListView list;
    private View emptyState;
    private TextView tvSubtitle, tvEmptyHint;
    private LinearLayout searchBar;
    private EditText etSearch;
    private TextView tabNovel, tabComic, tabRecent, tabFav;
    private final TextView[] tabs = new TextView[4];

    private final List<Book> all = new ArrayList<>();
    private final List<Book> shown = new ArrayList<>();
    private BookAdapter adapter;

    private String tab = "NOVEL";
    private String query = "";
    private Book pendingCover;
    private Dialog busyDialog;
    private boolean listLoaded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(0xFFF5F6F8);
            getWindow().setNavigationBarColor(0xFFF5F6F8);
        }

        list = findViewById(R.id.listBooks);
        emptyState = findViewById(R.id.emptyState);
        tvSubtitle = findViewById(R.id.tvSubtitle);
        tvEmptyHint = findViewById(R.id.tvEmptyHint);
        searchBar = findViewById(R.id.searchBar);
        etSearch = findViewById(R.id.etSearch);
        tabNovel = findViewById(R.id.tabNovel);
        tabComic = findViewById(R.id.tabComic);
        tabRecent = findViewById(R.id.tabRecent);
        tabFav = findViewById(R.id.tabFav);
        tabs[0] = tabNovel;
        tabs[1] = tabComic;
        tabs[2] = tabRecent;
        tabs[3] = tabFav;

        adapter = new BookAdapter(this, this);
        list.setAdapter(adapter);

        tabNovel.setOnClickListener(v -> switchTab("NOVEL"));
        tabComic.setOnClickListener(v -> switchTab("COMIC"));
        tabRecent.setOnClickListener(v -> switchTab("RECENT"));
        tabFav.setOnClickListener(v -> switchTab("FAV"));

        findViewById(R.id.btnSearch).setOnClickListener(v -> toggleSearch());
        findViewById(R.id.btnClearSearch).setOnClickListener(v -> {
            etSearch.setText("");
            toggleSearch();
        });
        findViewById(R.id.btnSort).setOnClickListener(v -> showSortDialog());
        findViewById(R.id.btnMenu).setOnClickListener(v -> showMenu());
        findViewById(R.id.btnImport).setOnClickListener(v -> showImportDialog());

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                refresh();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        switchTab("NOVEL", false);
        loadBooks();

        if (savedInstanceState == null) handleExternalIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleExternalIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (listLoaded) loadBooks();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        adapter.shutdown();
        dismissBusy();
    }

    // ------------------------------------------------------------------ data

    private void loadBooks() {
        List<Book> loaded = BookStorage.loadBooks(this);
        all.clear();
        all.addAll(loaded);
        listLoaded = true;
        refresh();
    }

    private void refresh() {
        shown.clear();
        for (Book b : all) {
            boolean match;
            switch (tab) {
                case "COMIC": match = b.isComic(); break;
                case "RECENT": match = b.lastReadTime > 0; break;
                case "FAV": match = b.favorite; break;
                default: match = !b.isComic(); break;
            }
            if (!match) continue;
            if (!query.isEmpty()) {
                String name = b.name == null ? "" : b.name.toLowerCase(Locale.ROOT);
                String author = b.author == null ? "" : b.author.toLowerCase(Locale.ROOT);
                if (!name.contains(query) && !author.contains(query)) continue;
            }
            shown.add(b);
        }
        sortShown();
        adapter.setItems(shown);

        boolean empty = shown.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        list.setVisibility(empty ? View.GONE : View.VISIBLE);

        long readMs = 0;
        for (Book b : all) readMs += b.totalReadTime;
        StringBuilder sub = new StringBuilder();
        sub.append(all.size()).append(" 本图书");
        if (readMs > 60000) {
            long min = readMs / 60000;
            if (min >= 60) sub.append("  ·  累计阅读 ").append(min / 60).append(" 小时");
            else sub.append("  ·  累计阅读 ").append(min).append(" 分钟");
        }
        if (query.isEmpty()) sub.append("  ·  ").append(tabName(tab));
        else sub.append("  ·  搜索“").append(query).append("”");
        tvSubtitle.setText(sub.toString());

        if (empty) {
            tvEmptyHint.setText(query.isEmpty()
                    ? getString(R.string.empty_hint)
                    : "没有匹配的图书");
        }
    }

    private void sortShown() {
        final int mode = Prefs.sortMode(this);
        Collections.sort(shown, new Comparator<Book>() {
            @Override public int compare(Book a, Book b) {
                switch (mode) {
                    case 1:
                        return safe(a.name).compareToIgnoreCase(safe(b.name));
                    case 2:
                        return Long.compare(b.addedTime, a.addedTime);
                    case 3:
                        return Long.compare(b.size, a.size);
                    default:
                        return Long.compare(b.lastReadTime, a.lastReadTime);
                }
            }
        });
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String tabName(String t) {
        switch (t) {
            case "COMIC": return "漫画";
            case "RECENT": return "最近阅读";
            case "FAV": return "收藏";
            default: return "小说";
        }
    }

    private void switchTab(String t) {
        switchTab(t, true);
    }

    private void switchTab(String t, boolean refresh) {
        tab = t;
        String[] keys = {"NOVEL", "COMIC", "RECENT", "FAV"};
        for (int i = 0; i < tabs.length; i++) {
            boolean active = keys[i].equals(t);
            tabs[i].setBackgroundResource(active ? R.drawable.bg_pill : 0);
            tabs[i].setTextColor(active ? 0xFF4C6FFF : 0xFF9AA3B2);
            tabs[i].setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        if (refresh) refresh();
    }

    private void toggleSearch() {
        if (searchBar.getVisibility() == View.VISIBLE) {
            searchBar.setVisibility(View.GONE);
            etSearch.setText("");
        } else {
            searchBar.setVisibility(View.VISIBLE);
            etSearch.requestFocus();
            try {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(etSearch, 0);
            } catch (Throwable ignored) {}
        }
    }

    // ------------------------------------------------------------------ adapter callbacks

    @Override
    public void onOpen(Book book) {
        book.lastReadTime = System.currentTimeMillis();
        BookStorage.saveBooks(this, all);
        Intent i = new Intent(this, ReaderActivity.class);
        i.putExtra("book_uri", book.uri);
        startActivity(i);
    }

    @Override
    public void onToggleFavorite(Book book) {
        book.favorite = !book.favorite;
        BookStorage.saveBooks(this, all);
        refresh();
        Toast.makeText(this, book.favorite ? "已加入收藏" : "已取消收藏", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onLongPress(Book book) {
        showBookOptions(book);
    }

    // ------------------------------------------------------------------ menus

    private void showImportDialog() {
        String[] items = {"📄  导入文件", "📁  导入文件夹", "🛠  批量整理与统计"};
        new AlertDialog.Builder(this)
                .setTitle("添加图书")
                .setItems(items, (d, which) -> {
                    if (which == 0) pickFile();
                    else if (which == 1) pickFolder();
                    else showMenu();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showMenu() {
        String[] items = {"导入文件", "导入文件夹", "排序方式", "备份书架", "恢复书架", "清理缓存", "关于"};
        new AlertDialog.Builder(this)
                .setTitle("更多")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: pickFile(); break;
                        case 1: pickFolder(); break;
                        case 2: showSortDialog(); break;
                        case 3: exportShelf(); break;
                        case 4: importShelf(); break;
                        case 5: clearCache(); break;
                        default: showAbout(); break;
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showSortDialog() {
        String[] items = {"最近阅读", "书名", "导入时间", "文件大小"};
        new AlertDialog.Builder(this)
                .setTitle("排序方式")
                .setSingleChoiceItems(items, Prefs.sortMode(this), (d, which) -> {
                    Prefs.setSortMode(this, which);
                    refresh();
                    d.dismiss();
                })
                .show();
    }

    private void showBookOptions(final Book book) {
        String[] items = {"重命名", "更换封面", "加入收藏 / 取消收藏", "书籍信息", "删除"};
        new AlertDialog.Builder(this)
                .setTitle(book.name)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: rename(book); break;
                        case 1: pickCover(book); break;
                        case 2: onToggleFavorite(book); break;
                        case 3: showInfo(book); break;
                        default: confirmDelete(book); break;
                    }
                })
                .show();
    }

    private void rename(final Book book) {
        final EditText et = new EditText(this);
        et.setText(book.name);
        et.setSingleLine(true);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle("重命名（仅显示名）")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (!n.isEmpty()) {
                        book.name = n;
                        BookStorage.saveBooks(this, all);
                        refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(final Book book) {
        new AlertDialog.Builder(this)
                .setTitle("删除")
                .setMessage("确定要把《" + book.name + "》从书架移除吗？\n（不会删除原文件）")
                .setPositiveButton("删除", (d, w) -> {
                    all.remove(book);
                    Covers.delete(this, book);
                    BookStorage.saveBooks(this, all);
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showInfo(Book book) {
        StringBuilder sb = new StringBuilder();
        sb.append("书名：").append(book.name).append('\n');
        if (book.author != null && !book.author.isEmpty()) sb.append("作者：").append(book.author).append('\n');
        sb.append("格式：").append(book.displayFormat()).append('\n');
        if (book.size > 0) sb.append("大小：").append(formatSize(book.size)).append('\n');
        sb.append("进度：").append(Math.round(book.progress * 100)).append("%\n");
        long min = book.totalReadTime / 60000;
        if (min > 0) sb.append("累计阅读：").append(min).append(" 分钟\n");
        if (book.lastReadTime > 0) {
            sb.append("上次阅读：").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                    .format(new java.util.Date(book.lastReadTime))).append('\n');
        }
        sb.append("路径：").append(book.uri);
        new AlertDialog.Builder(this)
                .setTitle("书籍信息")
                .setMessage(sb.toString())
                .setPositiveButton("好", null)
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("关于 MeaReading")
                .setMessage("MeaReading · 本地阅读器 · 无广告 · 不联网 · 不上传任何内容\n\n"
                        + "支持格式\n"
                        + "文本：TXT / EPUB / MOBI / AZW / AZW3 / PRC / FB2 / HTML / FBZ\n"
                        + "漫画：CBZ / CBR / CBT / ZIP / RAR / TAR / PDF\n\n"
                        + "功能\n"
                        + "· 自动目录、书签、全文搜索\n"
                        + "· 五种阅读主题、字号行距边距调节\n"
                        + "· 语音朗读、音量键翻页、夜间自动深色\n"
                        + "· 自动生成封面、批量导入文件夹\n\n"
                        + "版本 1.0.2")
                .setPositiveButton("好", null)
                .show();
    }

    // ------------------------------------------------------------------ picking

    private void pickFile() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            String[] mimes = {"application/epub+zip", "application/pdf", "application/x-mobipocket-ebook",
                    "application/vnd.amazon.ebook", "text/plain", "application/zip",
                    "application/x-cbz", "application/x-cbr", "application/x-rar-compressed",
                    "application/x-tar", "application/x-fictionbook+xml", "text/html"};
            i.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
            startActivityForResult(i, REQ_OPEN);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void pickFolder() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_TREE);
        } catch (Throwable t) {
            Toast.makeText(this, "当前系统不支持文件夹导入", Toast.LENGTH_SHORT).show();
        }
    }

    private void pickCover(Book book) {
        pendingCover = book;
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            startActivityForResult(i, REQ_COVER);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开相册", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        switch (requestCode) {
            case REQ_OPEN:
                takePermission(uri);
                importOne(uri);
                break;
            case REQ_TREE:
                takePermission(uri);
                importTree(uri);
                break;
            case REQ_COVER:
                if (pendingCover != null) applyCover(pendingCover, uri);
                break;
            case REQ_EXPORT:
                writeShelf(uri);
                break;
            case REQ_IMPORT_JSON:
                readShelf(uri);
                break;
            default:
                break;
        }
    }

    private void takePermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ import

    private void importOne(final Uri uri) {
        showBusy("正在导入…");
        new Thread(() -> {
            BookImporter.ImportResult r = BookImporter.importUri(this, uri, all, tab);
            runOnUiThread(() -> {
                dismissBusy();
                if (r.error != null) {
                    Toast.makeText(this, "导入失败：" + r.error, Toast.LENGTH_LONG).show();
                    return;
                }
                if (r.duplicate) {
                    Toast.makeText(this, "这本书已经在书架上了", Toast.LENGTH_SHORT).show();
                    onOpen(r.book);
                    return;
                }
                all.add(0, r.book);
                BookStorage.saveBooks(this, all);
                switchTab(r.book.isComic() ? "COMIC" : "NOVEL");
                refresh();
                Toast.makeText(this, "导入成功", Toast.LENGTH_SHORT).show();
                onOpen(r.book);
            });
        }).start();
    }

    private void importTree(final Uri treeUri) {
        showBusy("正在扫描文件夹…");
        new Thread(() -> {
            final List<Book> added = new ArrayList<>();
            final int count = BookImporter.importFolder(this, treeUri, all, added);
            runOnUiThread(() -> {
                dismissBusy();
                if (count > 0) {
                    all.addAll(0, added);
                    BookStorage.saveBooks(this, all);
                    refresh();
                    Toast.makeText(this, "成功导入 " + count + " 本书", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "没有找到支持的图书文件", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void applyCover(final Book book, final Uri uri) {
        takePermission(uri);
        showBusy("正在生成封面…");
        new Thread(() -> {
            try {
                book.coverPath = uri.toString();
                Covers.delete(this, book);
                Covers.ensure(this, book);
            } catch (Throwable ignored) {}
            runOnUiThread(() -> {
                dismissBusy();
                BookStorage.saveBooks(this, all);
                refresh();
                Toast.makeText(this, "封面已更新", Toast.LENGTH_SHORT).show();
            });
        }).start();
        pendingCover = null;
    }

    // ------------------------------------------------------------------ backup

    private void exportShelf() {
        try {
            String json = shelfJson();
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE, "moyue_shelf_" + new java.text.SimpleDateFormat("yyyyMMdd", Locale.US)
                    .format(new java.util.Date()) + ".json");
            startActivityForResult(i, REQ_EXPORT);
            pendingExport = json;
        } catch (Throwable t) {
            Toast.makeText(this, "无法导出", Toast.LENGTH_SHORT).show();
        }
    }

    private String pendingExport;

    private String shelfJson() {
        JSONArray arr = new JSONArray();
        for (Book b : all) {
            JSONObject o = new JSONObject();
            try {
                o.put("uri", b.uri);
                o.put("name", b.name);
                o.put("author", b.author);
                o.put("format", b.format);
                o.put("category", b.category);
                o.put("favorite", b.favorite);
                o.put("progress", b.progress);
                o.put("lastPage", b.lastPage);
                o.put("lastOffset", b.lastOffset);
                o.put("totalReadTime", b.totalReadTime);
                o.put("bookmarks", b.bookmarks);
            } catch (Throwable ignored) {}
            arr.put(o);
        }
        return arr.toString();
    }

    private void writeShelf(final Uri uri) {
        if (pendingExport == null) return;
        try {
            java.io.OutputStream out = getContentResolver().openOutputStream(uri);
            if (out == null) throw new Exception("无法写入");
            out.write(pendingExport.getBytes("UTF-8"));
            out.close();
            Toast.makeText(this, "书架已备份", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "备份失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void importShelf() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT_JSON);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开文件", Toast.LENGTH_SHORT).show();
        }
    }

    private void readShelf(final Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            byte[] data = Io.readAll(in);
            if (in != null) in.close();
            String json = new String(data, "UTF-8");
            JSONArray arr = new JSONArray(json);
            int added = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String u = o.optString("uri");
                if (u == null || u.isEmpty()) continue;
                boolean dup = false;
                for (Book b : all) if (u.equals(b.uri)) { dup = true; break; }
                if (dup) continue;
                Book b = new Book(u, o.optString("name"), o.optString("format"), o.optString("category", "NOVEL"));
                b.author = o.optString("author");
                b.favorite = o.optBoolean("favorite");
                b.progress = (float) o.optDouble("progress", 0);
                b.lastPage = o.optInt("lastPage");
                b.lastOffset = o.optInt("lastOffset");
                b.totalReadTime = o.optLong("totalReadTime");
                b.bookmarks = o.optString("bookmarks", "");
                b.addedTime = System.currentTimeMillis();
                all.add(b);
                added++;
            }
            BookStorage.saveBooks(this, all);
            refresh();
            Toast.makeText(this, "恢复了 " + added + " 本书", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "恢复失败：文件格式不正确", Toast.LENGTH_LONG).show();
        }
    }

    private void clearCache() {
        new AlertDialog.Builder(this)
                .setTitle("清理缓存")
                .setMessage("将清除导入副本、漫画解压缓存等临时文件，不会影响书架和阅读进度。")
                .setPositiveButton("清理", (d, w) -> {
                    long freed = CacheUtil.clearCache(this);
                    Toast.makeText(this, "已清理 " + formatSize(freed), Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------------ external intent

    private void handleExternalIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = intent.getData();
        if (uri == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0) {
            uri = intent.getClipData().getItemAt(0).getUri();
        }
        if (uri == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_SEND.equals(action)) {
            takePermission(uri);
            importOne(uri);
        }
    }

    // ------------------------------------------------------------------ busy dialog

    private void showBusy(String message) {
        dismissBusy();
        Dialog d = new Dialog(this, R.style.AppDialogTheme);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        box.setGravity(Gravity.CENTER_VERTICAL);
        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminate(true);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                (int) (26 * getResources().getDisplayMetrics().density),
                (int) (26 * getResources().getDisplayMetrics().density));
        box.addView(pb, pbLp);
        TextView tv = new TextView(this);
        tv.setText(message);
        tv.setTextSize(14);
        tv.setTextColor(0xFF15181D);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvLp.leftMargin = (int) (14 * getResources().getDisplayMetrics().density);
        box.addView(tv, tvLp);
        d.setContentView(box);
        d.setCancelable(false);
        try {
            Window w = d.getWindow();
            if (w != null) {
                w.setBackgroundDrawableResource(R.drawable.bg_dialog);
                w.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable ignored) {}
        busyDialog = d;
        d.show();
    }

    private void dismissBusy() {
        if (busyDialog != null) {
            try { busyDialog.dismiss(); } catch (Throwable ignored) {}
            busyDialog = null;
        }
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
    }

    @Override
    public void onBackPressed() {
        if (searchBar.getVisibility() == View.VISIBLE) {
            toggleSearch();
            return;
        }
        if (!"NOVEL".equals(tab)) {
            switchTab("NOVEL");
            return;
        }
        super.onBackPressed();
    }
}
