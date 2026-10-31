package com.Mealess.appaction;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.Mealess.appaction.core.EpubBook;
import com.Mealess.appaction.core.Io;
import com.Mealess.appaction.parser.EpubParser;
import com.Mealess.appaction.parser.Parser;
import com.Mealess.appaction.parser.ParserFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The reading surface: text pages, comic pages and EPUB chapters. */
public class ReaderActivity extends Activity {

    // views
    private FrameLayout pageContainer;
    private View readerRoot;
    private LinearLayout topBar, bottomBar;
    private TextView tvTitle, tvChapter, tvPageInfo, tvPageLabel, tvPercent;
    private ImageView btnBookmark, btnTts;
    private SeekBar sbProgress;
    private View loadingBar;

    // content views (created once and reused)
    private TextView pageText;
    private com.Mealess.appaction.ui.ReaderImageView pageImage;
    private WebView webView;
    private ScrollView scrollView;
    private LinearLayout scrollContent;

    // state
    private Parser parser;
    private Book book;
    private String bookUri;
    private int curPage;
    private int pageChars = ParserFactory.DEFAULT_PAGE_CHARS;
    private int fontSize, lineSpacing, margin, theme;
    private boolean scrollMode, reversed, volumeKeys, showPageInfo;
    private boolean bold;
    private boolean barsVisible;
    private boolean ready;
    private int loadedChapter = -1;
    private boolean webViewBroken;
    private int scrollStartPage, scrollLoadedEnd;
    private final Map<Integer, Integer> epubScroll = new HashMap<>();
    private Bitmap currentBitmap;
    private long readStartTime;

    // tts
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean ttsSpeaking;
    private GestureDetector gestures;

    private float downX, downY;
    private boolean edgeDrag;
    private boolean seeking;
    private boolean inBarArea;
    private int dragStartValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        readerRoot = findViewById(R.id.readerRoot);
        pageContainer = findViewById(R.id.pageContainer);
        topBar = findViewById(R.id.topBar);
        bottomBar = findViewById(R.id.bottomBar);
        tvTitle = findViewById(R.id.tvTitle);
        tvChapter = findViewById(R.id.tvChapter);
        tvPageInfo = findViewById(R.id.tvPageInfo);
        tvPageLabel = findViewById(R.id.tvPageLabel);
        tvPercent = findViewById(R.id.tvPercent);
        sbProgress = findViewById(R.id.sbProgress);
        btnBookmark = findViewById(R.id.btnBookmark);
        btnTts = findViewById(R.id.btnTts);
        loadingBar = findViewById(R.id.loadingBar);

        loadPrefs();

        bookUri = getIntent() == null ? null : getIntent().getStringExtra("book_uri");
        String name = getIntent() == null ? null : getIntent().getStringExtra("book_name");
        for (Book b : BookStorage.loadBooks(this)) {
            if (b.uri != null && b.uri.equals(bookUri)) {
                book = b;
                break;
            }
        }
        if (book == null) {
            book = new Book();
            book.uri = bookUri;
            book.name = name == null ? "阅读" : name;
            book.format = Book.extOf(name);
        }
        if (book.name == null || book.name.isEmpty()) book.name = name == null ? "阅读" : name;
        tvTitle.setText(book.name);

        initActions();
        applyTheme(false);
        pageContainer.post(new Runnable() {
            @Override public void run() { computePageChars(); }
        });

        loadingBar.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final Parser p = ParserFactory.create(ReaderActivity.this, book);
                    runOnUiThread(new Runnable() {
                        @Override public void run() { onParserReady(p); }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { onParserFailed(t); }
                    });
                }
            }
        }).start();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        goImmersive();
    }

    private void loadPrefs() {
        fontSize = Prefs.fontSize(this);
        lineSpacing = Prefs.lineSpacing(this);
        margin = Prefs.margin(this);
        theme = Prefs.theme(this);
        scrollMode = Prefs.scrollMode(this);
        reversed = Prefs.reversed(this);
        volumeKeys = Prefs.volumeKeys(this);
        showPageInfo = Prefs.pageInfo(this);
        bold = Prefs.bold(this);
        if (Prefs.autoNight(this)) {
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            if (hour >= 22 || hour < 7) {
                if (theme != Prefs.THEME_DARK && theme != Prefs.THEME_NIGHT_BLUE) theme = Prefs.THEME_DARK;
            }
        }
    }

    private int bg() { return Prefs.THEME_BG[theme]; }
    private int fg() { return Prefs.THEME_FG[theme]; }
    private int fg2() { return Prefs.THEME_SECONDARY[theme]; }
    private boolean dark() { return theme == Prefs.THEME_DARK || theme == Prefs.THEME_NIGHT_BLUE; }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    // ------------------------------------------------------------------ actions

    private void initActions() {
        findViewById(R.id.btnBack).setOnClickListener(v -> {
            saveProgress();
            finish();
        });
        btnBookmark.setOnClickListener(v -> toggleBookmark());
        btnTts.setOnClickListener(v -> toggleTts());

        findViewById(R.id.tabToc).setOnClickListener(v -> showToc());
        findViewById(R.id.tabSearch).setOnClickListener(v -> showSearch());
        findViewById(R.id.tabTheme).setOnClickListener(v -> showThemePicker());
        findViewById(R.id.tabFont).setOnClickListener(v -> showFontDialog());
        findViewById(R.id.tabSettings).setOnClickListener(v -> showSettings());

        sbProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    int count = parser == null ? 1 : Math.max(1, parser.pageCount());
                    int percent = Math.round(progress * 100f / Math.max(1, count - 1));
                    tvPageLabel.setText((progress + 1) + " / " + count);
                    tvPercent.setText(percent + "%");
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                seeking = false;
                if (parser != null) goToPage(seekBar.getProgress());
            }
        });

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                onReaderTap(e.getX());
                return true;
            }

            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 80) {
                    if (dx < 0) nextPage(); else prevPage();
                    return true;
                }
                return false;
            }

            @Override public void onLongPress(MotionEvent e) {
                if (!barsVisible) setBars(true);
            }
        });
    }

    private void onReaderTap(float x) {
        if (!ready) {
            setBars(!barsVisible);
            return;
        }
        float w = pageContainer.getWidth();
        if (x < w * 0.33f) {
            if (reversed) nextPage(); else prevPage();
        } else if (x > w * 0.67f) {
            if (reversed) prevPage(); else nextPage();
        } else {
            setBars(!barsVisible);
        }
    }

    // ------------------------------------------------------------------ parser lifecycle

    private void onParserFailed(Throwable t) {
        loadingBar.setVisibility(View.GONE);
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        new AlertDialog.Builder(this)
                .setTitle("无法打开")
                .setMessage("这本书打不开：\n\n" + msg)
                .setPositiveButton("返回", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    private void onParserReady(final Parser p) {
        parser = p;
        loadingBar.setVisibility(View.GONE);
        ready = true;
        if (p.isImage()) scrollMode = false;   // comics always use page mode
        computePageChars();

        int target;
        if (p.reflow(pageChars, book.lastOffset) >= 0) {
            target = p.pageForOffset(book.lastOffset);
        } else {
            target = book.lastPage;
        }
        if (target < 0) target = 0;
        if (target >= p.pageCount()) target = Math.max(0, p.pageCount() - 1);
        curPage = target;
        showPage(curPage);
        updateBarsForParser();
        setBars(false);
        updateProgressUi();
        readStartTime = System.currentTimeMillis();
    }

    private void updateBarsForParser() {
        boolean hasChapters = parser != null && parser.chapterCount() > 0;
        findViewById(R.id.tabToc).setVisibility(hasChapters ? View.VISIBLE : View.INVISIBLE);
        updateBookmarkIcon();
        updateChapterLabel();
        tvPercent.setVisibility(showPageInfo ? View.VISIBLE : View.GONE);
        tvPageInfo.setVisibility(showPageInfo ? View.VISIBLE : View.GONE);
    }

    private void updateChapterLabel() {
        if (parser == null) return;
        String chapter = "";
        if (parser.isHtml()) {
            chapter = parser.chapterTitle(curPage);
        } else if (parser.chapterCount() > 0) {
            int ci = parser.pageChapter(curPage);
            if (ci >= 0) chapter = parser.chapterTitle(ci);
        }
        tvChapter.setText(chapter);
        tvChapter.setVisibility(chapter.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------ page size

    private void computePageChars() {
        if (pageText == null) {
            pageText = new TextView(this);
            pageText.setTextColor(fg());
            pageText.setLineSpacing(0, lineSpacing / 100f);
        pageText.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            pageText.setTextIsSelectable(true);
            pageText.setIncludeFontPadding(false);
        }
        pageText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fontSize);
        int pad = (int) dp(margin);
        pageText.setPadding(pad, pad, pad, pad);
        if (parser == null || parser.isImage() || parser.isHtml()) return;

        int w = pageContainer.getWidth();
        int h = pageContainer.getHeight();
        if (w <= 0 || h <= 0) return;
        int availW = Math.max(80, w - pad * 2);
        int availH = Math.max(80, h - pad * 2);
        Paint paint = pageText.getPaint();
        String sample = parser.text(curPage).toString();
        int n = Math.min(400, sample.length());
        float total = 0;
        for (int i = 0; i < n; i++) total += paint.measureText(sample, i, i + 1);
        float avg = n > 0 ? total / n : paint.getTextSize() * 0.5f;
        avg = Math.max(avg, paint.getTextSize() * 0.3f);
        int cols = Math.max(6, (int) (availW / avg));
        int lineH = Math.max(1, (int) (paint.getFontSpacing() * (lineSpacing / 100f)));
        int rows = Math.max(3, availH / lineH);
        int chars = cols * rows;
        chars = Math.max(300, Math.min(9000, chars));

        if (chars != pageChars) {
            int anchor = parser.offsetForPage(curPage);
            pageChars = chars;
            int newPage = parser.reflow(chars, anchor);
            if (newPage >= 0) curPage = newPage;
        }
    }

    // ------------------------------------------------------------------ rendering

    private void showPage(int page) {
        if (parser == null) return;
        int count = parser.pageCount();
        if (count <= 0) {
            loadingBar.setVisibility(View.GONE);
            Toast.makeText(this, "这本书里没有可显示的内容", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        curPage = Math.max(0, Math.min(page, count - 1));

        if (parser.isHtml()) {
            pageText.setVisibility(View.GONE);
            showEpub(curPage, false);
        } else if (parser.isImage()) {
            showImage(curPage);
        } else if (scrollMode) {
            showScroll(curPage);
        } else {
            showText(curPage);
        }

        updateProgressUi();
        updateChapterLabel();
        updateBookmarkIcon();
        updatePageInfo();
    }

    private void showText(int page) {
        detachAll();
        if (pageText == null) computePageChars();
        pageText.setTextColor(fg());
        pageText.setBackgroundColor(bg());
        pageText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fontSize);
        pageText.setLineSpacing(0, lineSpacing / 100f);
        pageText.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        int pad = (int) dp(margin);
        pageText.setPadding(pad, pad, pad, pad);
        pageText.setVisibility(View.VISIBLE);
        pageText.setText(parser.text(page));
        if (pageText.getParent() == null) {
            pageContainer.addView(pageText, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private void showImage(int page) {
        detachAll();
        if (pageImage == null) {
            pageImage = new com.Mealess.appaction.ui.ReaderImageView(this);
            pageImage.setListener(new com.Mealess.appaction.ui.ReaderImageView.Listener() {
                @Override public void onTap(float x) { onReaderTap(x); }
                @Override public void onSwipeLeft() { nextPage(); }
                @Override public void onSwipeRight() { prevPage(); }
                @Override public void onLongPress() { setBars(!barsVisible); }
            });
        }
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        Bitmap bmp = parser.image(page, w, h);
        Bitmap old = currentBitmap;
        currentBitmap = bmp;
        pageImage.setImageBitmap(bmp);
        if (old != null && old != bmp && !old.isRecycled()) old.recycle();
        if (pageImage.getParent() == null) {
            pageContainer.addView(pageImage, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (bmp == null) {
            Toast.makeText(this, "该页图片无法解码", Toast.LENGTH_SHORT).show();
        }
    }

    private void detachAll() {
        pageContainer.removeAllViews();
    }

    // ------------------------------------------------------------------ epub

    private void ensureWebView() {
        if (webView != null) return;
        webView = new WebView(this);        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(false);
        s.setDomStorageEnabled(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setLoadsImagesAutomatically(true);
        webView.setBackgroundColor(bg());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return intercept(request.getUrl().toString());
            }

            @Override
            @SuppressWarnings("deprecation")
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return intercept(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !url.startsWith("https://epub.local/");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (parser instanceof EpubParser) {
                    Integer y = epubScroll.get(loadedChapter);
                    if (y != null && y > 0) view.scrollTo(0, y);
                }
            }
        });
    }

    private WebResourceResponse intercept(String url) {
        if (!(parser instanceof EpubParser)) return null;
        if (url == null || !url.startsWith("https://epub.local/")) return null;
        String path = url.substring("https://epub.local/".length());
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        EpubBook epub = ((EpubParser) parser).book();
        InputStream in = epub.open(path);
        if (in == null) {
            return new WebResourceResponse("text/plain", "utf-8", new java.io.ByteArrayInputStream(new byte[0]));
        }
        String mime = epub.mimeOf(path);
        String encoding = mime.startsWith("text/") || mime.contains("xml") || mime.contains("javascript")
                ? "utf-8" : null;
        return new WebResourceResponse(mime, encoding, in);
    }

    private String injectCss(String html) {
        StringBuilder css = new StringBuilder();
        css.append("<style>");
        css.append("html{-webkit-text-size-adjust:100%;}");
        css.append("body{margin:0;padding:").append((int) dp(margin)).append("px;");
        css.append("background-color:transparent !important;color:").append(hex(fg())).append(" !important;");
        css.append("font-size:").append(fontSize).append("px !important;");
        css.append("line-height:").append(lineSpacing / 100f).append(" !important;");
        css.append("word-wrap:break-word;text-align:justify;");
        css.append("font-weight:").append(bold ? "600" : "400").append(";}");
        css.append("p{text-indent:2em;margin:0 0 0.85em 0;}");
        css.append("h1,h2,h3{font-weight:600;line-height:1.35;margin:0.8em 0 0.6em;color:").append(hex(fg())).append(";}");
        css.append("h1{font-size:1.3em}h2{font-size:1.18em}h3{font-size:1.08em}");
        css.append("img{max-width:100% !important;height:auto !important;display:block;margin:0.8em auto;}");
        css.append("a{color:").append(hex(fg2())).append(";}");
        css.append("body *{background-color:transparent !important;}");
        css.append("::-webkit-scrollbar{width:0;height:0;}");
        css.append("</style>");
        String head = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=3.0\">";
        String out = html;
        if (out.toLowerCase(Locale.ROOT).contains("</head>")) {
            int idx = out.toLowerCase(Locale.ROOT).indexOf("</head>");
            out = out.substring(0, idx) + head + css + out.substring(idx);
        } else {
            out = "<!DOCTYPE html><html><head>" + head + css + "</head><body>" + out + "</body></html>";
        }
        return out;
    }

    private static String hex(int color) {
        return String.format(Locale.US, "#%06X", 0xFFFFFF & color);
    }

    private void showEpub(int chapter, boolean force) {
        if (webViewBroken) {
            showHtmlAsText(chapter);
            return;
        }
        try {
            ensureWebView();
        } catch (Throwable t) {
            // Some devices ship without a usable WebView provider: degrade to plain text
            webViewBroken = true;
            showHtmlAsText(chapter);
            return;
        }
        webView.setBackgroundColor(bg());
        if (webView.getParent() == null) {
            pageContainer.removeAllViews();
            pageContainer.addView(webView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if (pageContainer.getChildCount() == 0 || pageContainer.getChildAt(0) != webView) {
            pageContainer.removeAllViews();
            pageContainer.addView(webView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (!force && loadedChapter == chapter) return;
        if (loadedChapter >= 0) {
            epubScroll.put(loadedChapter, webView.getScrollY());
        }
        loadedChapter = chapter;
        String html = parser.html(chapter);
        if (html == null || html.trim().isEmpty()) {
            Toast.makeText(this, "本章内容为空", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            webView.loadDataWithBaseURL(parser.baseUrl(chapter), injectCss(html), "text/html", "utf-8", null);
        } catch (Throwable t) {
            webViewBroken = true;
            showHtmlAsText(chapter);
        }
    }

    /** Fallback rendering for devices where the WebView cannot be used. */
    private void showHtmlAsText(int chapter) {
        if (pageText == null) computePageChars();
        if (pageText == null) return;
        detachAll();
        pageText.setVisibility(View.VISIBLE);
        pageText.setTextColor(fg());
        pageText.setBackgroundColor(bg());
        pageText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fontSize);
        pageText.setLineSpacing(0, lineSpacing / 100f);
        pageText.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        int pad = (int) dp(margin);
        pageText.setPadding(pad, pad, pad, pad);
        pageText.setText(parser.text(chapter));
        if (pageText.getParent() == null) {
            pageContainer.addView(pageText, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    // ------------------------------------------------------------------ scroll mode

    private void showScroll(int page) {
        if (scrollView == null) {
            scrollView = new ScrollView(this);
            scrollView.setBackgroundColor(bg());
            scrollContent = new LinearLayout(this);
            scrollContent.setOrientation(LinearLayout.VERTICAL);
            scrollView.addView(scrollContent, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            scrollView.setOnScrollChangeListener(new View.OnScrollChangeListener() {
                @Override
                public void onScrollChange(View v, int sx, int sy, int osx, int osy) {
                    onScrollChangedInternal(sy);
                }
            });
            pageContainer.addView(scrollView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if (scrollView.getParent() == null) {
            detachAll();
            pageContainer.addView(scrollView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        scrollView.setBackgroundColor(bg());
        int start = Math.max(0, page - 1);
        if (scrollStartPage != start || scrollContent.getChildCount() == 0 || scrollContent.getChildAt(0).getTag() == null
                || (Integer) scrollContent.getChildAt(0).getTag() != start) {
            rebuildScroll(start);
        }
    }

    private void rebuildScroll(int start) {
        scrollContent.removeAllViews();
        scrollStartPage = start;
        scrollLoadedEnd = start;
        appendScroll(4);
    }

    private void appendScroll(int count) {
        if (parser == null) return;
        int end = Math.min(parser.pageCount(), scrollLoadedEnd + count);
        for (int i = scrollLoadedEnd; i < end; i++) {
            TextView tv = new TextView(this);
            tv.setText(parser.text(i));
            tv.setTextColor(fg());
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fontSize);
            tv.setLineSpacing(0, lineSpacing / 100f);
            int pad = (int) dp(margin);
            tv.setPadding(pad, pad / 2, pad, pad / 2);
            tv.setTextIsSelectable(true);
            tv.setTag(i);
            scrollContent.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        scrollLoadedEnd = end;
    }

    private void onScrollChangedInternal(int y) {
        if (parser == null || scrollContent == null) return;
        int count = scrollContent.getChildCount();
        int visible = scrollStartPage;
        for (int i = 0; i < count; i++) {
            View child = scrollContent.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof Integer && child.getTop() <= y + 4) visible = (Integer) tag;
        }
        if (visible != curPage) {
            curPage = visible;
            updateProgressUi();
            updateChapterLabel();
        }
        View last = scrollContent.getChildAt(count - 1);
        if (last != null && y + scrollView.getHeight() >= last.getBottom() - 600
                && scrollLoadedEnd < parser.pageCount()) {
            appendScroll(4);
        }
    }

    // ------------------------------------------------------------------ navigation

    private void nextPage() {
        if (parser == null) return;
        if (parser.isHtml()) {
            if (webViewBroken || webView == null) {
                if (curPage < parser.pageCount() - 1) goToPage(curPage + 1);
                else Toast.makeText(this, "已经是最后一章了", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!webView.pageDown(false)) {
                if (curPage < parser.pageCount() - 1) {
                    goToPage(curPage + 1);
                } else {
                    Toast.makeText(this, "已经是最后一章了", Toast.LENGTH_SHORT).show();
                }
            } else {
                updateProgressUi();
            }
            return;
        }
        if (scrollMode) {
            if (!parser.isImage()) {
                scrollView.smoothScrollBy(0, Math.max(1, pageContainer.getHeight() - (int) dp(40)));
                return;
            }
        }
        if (curPage < parser.pageCount() - 1) goToPage(curPage + 1);
        else Toast.makeText(this, "已经是最后一页了", Toast.LENGTH_SHORT).show();
    }

    private void prevPage() {
        if (parser == null) return;
        if (parser.isHtml()) {
            if (webViewBroken || webView == null) {
                if (curPage > 0) goToPage(curPage - 1);
                else Toast.makeText(this, "已经是第一章了", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!webView.pageUp(false)) {
                if (curPage > 0) goToPage(curPage - 1);
                else Toast.makeText(this, "已经是第一章了", Toast.LENGTH_SHORT).show();
            } else {
                updateProgressUi();
            }
            return;
        }
        if (scrollMode) {
            if (!parser.isImage()) {
                scrollView.smoothScrollBy(0, -Math.max(1, pageContainer.getHeight() - (int) dp(40)));
                return;
            }
        }
        if (curPage > 0) goToPage(curPage - 1);
        else Toast.makeText(this, "已经是第一页了", Toast.LENGTH_SHORT).show();
    }

    private void goToPage(int page) {
        if (parser == null) return;
        int count = parser.pageCount();
        curPage = Math.max(0, Math.min(page, count - 1));
        if (parser.isHtml()) {
            showEpub(curPage, false);
        } else if (parser.isImage()) {
            showImage(curPage);
        } else if (scrollMode) {
            showScroll(curPage);
            scrollView.scrollTo(0, 0);
        } else {
            showText(curPage);
        }
        updateProgressUi();
        updateChapterLabel();
        updateBookmarkIcon();
        updatePageInfo();
    }

    private void goToChapter(int index) {
        if (parser == null) return;
        if (parser.chapterCount() == 0) return;
        int page = parser.chapterPage(index);
        goToPage(page);
        if (parser.isHtml()) showEpub(index, false);
    }

    // ------------------------------------------------------------------ ui updates

    private void updateProgressUi() {
        if (parser == null) return;
        int count = parser.pageCount();
        float fraction = count <= 1 ? 0f : (float) curPage / (count - 1);
        sbProgress.setMax(Math.max(1, count - 1));
        sbProgress.setProgress(Math.min(count - 1, curPage));
        int percent = Math.round(Math.max(0f, Math.min(1f, fraction)) * 100f);
        tvPageLabel.setText((curPage + 1) + " / " + count);
        tvPercent.setText(percent + "%");
        book.progress = fraction;
    }

    private void updatePageInfo() {
        if (!showPageInfo) {
            tvPageInfo.setVisibility(View.GONE);
            return;
        }
        tvPageInfo.setVisibility(View.VISIBLE);
        int count = parser == null ? 0 : parser.pageCount();
        int percent = count <= 1 ? 0 : Math.round((curPage / (float) (count - 1)) * 100f);
        String chapter = "";
        if (parser != null && parser.chapterCount() > 0) {
            int ci = parser.isHtml() ? curPage : parser.pageChapter(curPage);
            if (ci >= 0) chapter = parser.chapterTitle(ci);
        }
        String s = (curPage + 1) + " / " + count + "  ·  " + percent + "%";
        if (!chapter.isEmpty()) s = chapter + "\n" + s;
        tvPageInfo.setText(s);
    }

    private void updateBookmarkIcon() {
        boolean marked = isBookmarked(curPage);
        btnBookmark.setImageResource(R.drawable.ic_bookmark);
        btnBookmark.setColorFilter(marked ? 0xFFF0A020 : fg2());
    }

    private void setBars(boolean show) {
        if (show == barsVisible) return;
        barsVisible = show;
        if (show) {
            topBar.setVisibility(View.VISIBLE);
            bottomBar.setVisibility(View.VISIBLE);
            topBar.setTranslationY(-topBar.getHeight());
            bottomBar.setTranslationY(bottomBar.getHeight());
            topBar.animate().translationY(0).setDuration(200).start();
            bottomBar.animate().translationY(0).setDuration(200).start();
            goImmersive();
        } else {
            topBar.animate().translationY(-topBar.getHeight()).setDuration(160)
                    .withEndAction(() -> topBar.setVisibility(View.INVISIBLE)).start();
            bottomBar.animate().translationY(bottomBar.getHeight()).setDuration(160)
                    .withEndAction(() -> bottomBar.setVisibility(View.INVISIBLE)).start();
            goImmersive();
        }
    }

    private void goImmersive() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void applyTheme(boolean rerender) {
        readerRoot.setBackgroundColor(bg());
        topBar.setBackgroundColor(dark() ? 0xFF1C2027 : 0xFFFDFDFD);
        bottomBar.setBackgroundColor(dark() ? 0xFF1C2027 : 0xFFFDFDFD);
        tvTitle.setTextColor(fg());
        tvChapter.setTextColor(fg2());
        tvPageLabel.setTextColor(fg2());
        tvPercent.setTextColor(fg2());
        btnBookmark.setColorFilter(fg2());
        btnTts.setColorFilter(fg2());
        findViewById(R.id.btnBack).setBackgroundColor(Color.TRANSPARENT);
        ((ImageView) findViewById(R.id.btnBack)).setColorFilter(fg2());
        tintChildren(topBar);
        tintChildren(bottomBar);
        if (pageText != null) {
            pageText.setTextColor(fg());
            pageText.setBackgroundColor(bg());
        }
        if (webView != null) webView.setBackgroundColor(bg());
        if (scrollView != null) {
            scrollView.setBackgroundColor(bg());
            for (int i = 0; i < scrollContent.getChildCount(); i++) {
                View c = scrollContent.getChildAt(i);
                if (c instanceof TextView) ((TextView) c).setTextColor(fg());
            }
        }
        if (rerender && ready) {
            if (parser != null && parser.isHtml()) {
                showEpub(curPage, true);
            } else if (parser != null && parser.isImage()) {
                goToPage(curPage);
            } else if (parser != null) {
                goToPage(curPage);
            }
            updateBookmarkIcon();
        }
    }

    private void tintChildren(LinearLayout bar) {
        int color = dark() ? 0xFFB4BAC4 : 0xFF8A9099;
        tintRecursive(bar, color);
    }

    private void tintRecursive(View v, int color) {
        if (v instanceof ImageView && v.getId() != R.id.btnBookmark && v.getId() != R.id.btnBack) {
            ((ImageView) v).setColorFilter(color);
        }
        if (v instanceof TextView && v.getId() != R.id.tvTitle && v.getId() != R.id.tvChapter) {
            ((TextView) v).setTextColor(color);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) tintRecursive(g.getChildAt(i), color);
        }
    }

    // ------------------------------------------------------------------ progress persistence

    private void saveProgress() {
        if (parser == null || book == null) return;
        if (parser.isHtml() && webView != null && loadedChapter >= 0) {
            epubScroll.put(loadedChapter, webView.getScrollY());
        }
        book.lastPage = curPage;
        book.lastChapter = parser.isHtml() ? curPage : parser.pageChapter(curPage);
        int off = parser.offsetForPage(curPage);
        if (off >= 0) book.lastOffset = off;
        book.lastReadTime = System.currentTimeMillis();
        long now = System.currentTimeMillis();
        long cost = now - readStartTime;
        if (readStartTime > 0 && cost > 1000 && cost < 4L * 60 * 60 * 1000) {
            book.totalReadTime += cost;
        }
        readStartTime = now;
        List<Book> all = BookStorage.loadBooks(this);
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            if (book.uri != null && book.uri.equals(all.get(i).uri)) {
                all.set(i, book.copy());
                found = true;
                break;
            }
        }
        if (!found && book.uri != null) all.add(0, book.copy());
        BookStorage.saveBooks(this, all);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveProgress();
        if (ttsSpeaking) stopTts();
    }

    @Override
    protected void onResume() {
        super.onResume();
        readStartTime = System.currentTimeMillis();
        goImmersive();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        saveProgress();
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Throwable ignored) {}
            tts = null;
        }
        if (webView != null) {
            try {
                webView.loadUrl("about:blank");
                webView.destroy();
            } catch (Throwable ignored) {}
            webView = null;
        }
        if (parser != null) {
            parser.close();
            parser = null;
        }
        if (currentBitmap != null && !currentBitmap.isRecycled()) {
            currentBitmap.recycle();
            currentBitmap = null;
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        float w = pageContainer.getWidth();
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = ev.getX();
            downY = ev.getY();
            edgeDrag = false;
            inBarArea = barsVisible && (insideBar(topBar, ev) || insideBar(bottomBar, ev));
            if (!inBarArea && ready && parser != null && !parser.isImage() && !scrollMode && w > 0) {
                if (downX < w * 0.18f) {
                    edgeDrag = true;
                    dragStartValue = Math.round(getWindow().getAttributes().screenBrightness * 100);
                    if (dragStartValue < 0) dragStartValue = 60;
                } else if (downX > w * 0.82f) {
                    edgeDrag = true;
                    dragStartValue = fontSize;
                }
            }
        }
        if (ev.getActionMasked() == MotionEvent.ACTION_MOVE && edgeDrag) {
            float dy = ev.getY() - downY;
            if (Math.abs(dy) > 12 && Math.abs(ev.getX() - downX) < 80) {
                if (downX < w * 0.18f) setBrightness(dragStartValue + (int) (dy / 6));
                else setFontSizeLive(dragStartValue - (int) (dy / 18));
                return true;
            }
        }
        if (inBarArea || seeking) {
            // let the toolbar / seek bar handle everything
            return super.dispatchTouchEvent(ev);
        }
        if (parser != null && parser.isImage() && !scrollMode && ev.getPointerCount() == 1) {
            // the image view handles taps and zoom itself
            return super.dispatchTouchEvent(ev);
        }
        if (gestures != null) gestures.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    private boolean insideBar(View bar, MotionEvent ev) {
        if (bar == null || bar.getVisibility() != View.VISIBLE) return false;
        int[] loc = new int[2];
        bar.getLocationOnScreen(loc);
        float x = ev.getRawX(), y = ev.getRawY();
        return x >= loc[0] && x <= loc[0] + bar.getWidth()
                && y >= loc[1] && y <= loc[1] + bar.getHeight();
    }

    private void setBrightness(int percent) {
        int p = Math.max(5, Math.min(100, percent));
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = p / 100f;
        getWindow().setAttributes(lp);
    }

    private void setFontSizeLive(int size) {
        int s = Math.max(12, Math.min(42, size));
        if (s == fontSize) return;
        fontSize = s;
        Prefs.setFontSize(this, fontSize);
        if (parser != null && !parser.isHtml() && !parser.isImage()) {
            int anchor = parser.offsetForPage(curPage);
            computePageChars();
            int np = parser.reflow(pageChars, anchor);
            if (np >= 0) curPage = np;
            goToPage(curPage);
        } else if (parser != null && parser.isHtml()) {
            showEpub(curPage, true);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (volumeKeys && event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                nextPage();
                return true;
            }
            if (code == KeyEvent.KEYCODE_VOLUME_UP) {
                prevPage();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        pageContainer.post(new Runnable() {
            @Override public void run() {
                if (parser != null && !parser.isHtml() && !parser.isImage()) {
                    int anchor = parser.offsetForPage(curPage);
                    computePageChars();
                    int np = parser.reflow(pageChars, anchor);
                    if (np >= 0) curPage = np;
                    goToPage(curPage);
                } else if (parser != null) {
                    goToPage(curPage);
                }
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (barsVisible) {
            setBars(false);
            return;
        }
        saveProgress();
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ bookmarks

    private boolean isBookmarked(int page) {
        for (int p : bookmarkPages()) if (p == page) return true;
        return false;
    }

    private List<Integer> bookmarkPages() {
        List<Integer> pages = new ArrayList<>();
        if (book.bookmarks == null || book.bookmarks.isEmpty()) return pages;
        try {
            JSONArray arr = new JSONArray(book.bookmarks);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) pages.add(o.optInt("p"));
            }
        } catch (Throwable ignored) {}
        return pages;
    }

    private void toggleBookmark() {
        try {
            JSONArray arr = new JSONArray(book.bookmarks == null || book.bookmarks.isEmpty() ? "[]" : book.bookmarks);
            JSONArray out = new JSONArray();
            boolean removed = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (o.optInt("p") == curPage) {
                    removed = true;
                    continue;
                }
                out.put(o);
            }
            if (!removed) {
                JSONObject o = new JSONObject();
                o.put("p", curPage);
                String t = parser != null && parser.chapterCount() > 0
                        ? parser.chapterTitle(parser.isHtml() ? curPage : Math.max(0, parser.pageChapter(curPage))) : "";
                o.put("t", t);
                o.put("time", System.currentTimeMillis());
                out.put(o);
            }
            book.bookmarks = out.toString();
            saveProgress();
            updateBookmarkIcon();
            Toast.makeText(this, removed ? "已移除书签" : "已添加书签", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "书签操作失败", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ dialogs

    private Dialog sheet(int layoutId) {
        Dialog d = new Dialog(this, R.style.BottomSheetTheme);
        d.setContentView(layoutId);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setBackgroundDrawableResource(R.drawable.bg_sheet);
        }
        return d;
    }

    private void showToc() {
        if (parser == null) return;
        final Dialog d = sheet(R.layout.dialog_list);
        TextView title = d.findViewById(R.id.tvDialogTitle);
        final TextView count = d.findViewById(R.id.tvDialogCount);
        final ListView list = d.findViewById(R.id.listDialog);
        final View searchRow = d.findViewById(R.id.searchRow);
        final android.widget.EditText input = d.findViewById(R.id.etDialogSearch);
        final TextView empty = d.findViewById(R.id.tvDialogEmpty);
        title.setText("目录");

        final List<String> allTitles = new ArrayList<>();
        final List<Integer> allPages = new ArrayList<>();
        int n = parser.chapterCount();
        if (n > 0) {
            for (int i = 0; i < n; i++) {
                String t = parser.chapterTitle(i);
                if (t == null || t.trim().isEmpty()) t = "第 " + (i + 1) + " 章";
                allTitles.add(t.trim());
                allPages.add(parser.chapterPage(i));
            }
        } else {
            int total = parser.pageCount();
            int step = Math.max(1, total / 30);
            for (int p = 0; p < total; p += step) {
                allTitles.add("第 " + (p + 1) + " 页");
                allPages.add(p);
            }
        }

        final List<String> titles = new ArrayList<>(allTitles);
        final List<Integer> pages = new ArrayList<>(allPages);
        count.setText(titles.size() + " 项");

        int screenH = getResources().getDisplayMetrics().heightPixels;
        list.getLayoutParams().height = (int) (screenH * 0.55f);
        final SimpleListAdapter adapter = new SimpleListAdapter(titles);
        list.setAdapter(adapter);
        final int currentChapter = parser.isHtml() ? curPage
                : (parser.chapterCount() > 0 ? Math.max(0, parser.pageChapter(curPage)) : 0);
        list.setSelection(Math.max(0, Math.min(titles.size() - 1, currentChapter)));
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                d.dismiss();
                goToPage(pages.get(position));
            }
        });

        // long books: filter chapter titles while typing
        if (allTitles.size() > 12) {
            searchRow.setVisibility(View.VISIBLE);
            input.setHint("搜索章节");
            input.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int c) {
                    String q = s.toString().trim().toLowerCase(Locale.ROOT);
                    titles.clear();
                    pages.clear();
                    for (int i = 0; i < allTitles.size(); i++) {
                        if (q.isEmpty() || allTitles.get(i).toLowerCase(Locale.ROOT).contains(q)) {
                            titles.add(allTitles.get(i));
                            pages.add(allPages.get(i));
                        }
                    }
                    adapter.notifyDataSetChanged();
                    count.setText(q.isEmpty() ? titles.size() + " 项" : titles.size() + " / " + allTitles.size());
                    empty.setVisibility(titles.isEmpty() ? View.VISIBLE : View.GONE);
                    empty.setText("没有匹配的章节");
                }
                @Override public void afterTextChanged(android.text.Editable s) {}
            });
        } else {
            searchRow.setVisibility(View.GONE);
        }

        d.findViewById(R.id.btnDialogClose).setOnClickListener(v -> d.dismiss());
        empty.setVisibility(View.GONE);
        d.show();
    }

    private void showSearch() {
        if (parser == null) return;
        if (parser.isImage()) {
            Toast.makeText(this, "漫画暂不支持全文搜索", Toast.LENGTH_SHORT).show();
            return;
        }
        final Dialog d = sheet(R.layout.dialog_list);
        TextView title = d.findViewById(R.id.tvDialogTitle);
        final TextView count = d.findViewById(R.id.tvDialogCount);
        final ListView list = d.findViewById(R.id.listDialog);
        final View searchRow = d.findViewById(R.id.searchRow);
        final android.widget.EditText input = d.findViewById(R.id.etDialogSearch);
        final TextView empty = d.findViewById(R.id.tvDialogEmpty);
        title.setText("全文搜索");
        searchRow.setVisibility(View.VISIBLE);
        count.setText("");

        int screenH = getResources().getDisplayMetrics().heightPixels;
        list.getLayoutParams().height = (int) (screenH * 0.5f);

        final List<String> labels = new ArrayList<>();
        final List<String> subs = new ArrayList<>();
        final List<Integer> pages = new ArrayList<>();
        final SimpleListAdapter adapter = new SimpleListAdapter(labels, subs);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                d.dismiss();
                goToPage(pages.get(position));
            }
        });

        View.OnClickListener doSearch = v -> {
            String q = input.getText().toString().trim();
            labels.clear();
            subs.clear();
            pages.clear();
            if (q.isEmpty()) {
                adapter.notifyDataSetChanged();
                return;
            }
            count.setText("搜索中…");
            final String query = q;
            new Thread(() -> {
                final List<Integer> hits = new ArrayList<>();
                final List<String> snippets = new ArrayList<>();
                try {
                    if (parser != null) {
                        hits.addAll(parser.search(query, 200));
                        for (int p : hits) snippets.add(parser.snippet(p, query));
                    }
                } catch (Throwable ignored) {}
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    for (int i = 0; i < hits.size() && i < snippets.size(); i++) {
                        int p = hits.get(i);
                        String chapter = "";
                        try {
                            if (parser != null && parser.chapterCount() > 0) {
                                chapter = parser.chapterTitle(parser.isHtml() ? p : Math.max(0, parser.pageChapter(p)));
                            }
                        } catch (Throwable ignored) {}
                        labels.add(TextUtils.isEmpty(chapter) ? "第 " + (p + 1) + " 页" : chapter);
                        subs.add(snippets.get(i));
                        pages.add(p);
                    }
                    adapter.notifyDataSetChanged();
                    count.setText(hits.size() + " 处");
                    empty.setVisibility(labels.isEmpty() ? View.VISIBLE : View.GONE);
                    empty.setText("没有找到“" + query + "”");
                    if (!labels.isEmpty()) list.setSelection(0);
                });
            }).start();
        };
        d.findViewById(R.id.btnDialogSearch).setOnClickListener(doSearch);
        input.setOnEditorActionListener((v, actionId, event) -> {
            doSearch.onClick(v);
            return true;
        });
        d.findViewById(R.id.btnDialogClose).setOnClickListener(v -> d.dismiss());
        d.show();
        input.requestFocus();
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, 0);
        } catch (Throwable ignored) {}
    }

    private void showThemePicker() {
        final Dialog d = sheet(R.layout.dialog_settings);
        fillSettings(d);
    }

    private void showFontDialog() {
        final Dialog d = sheet(R.layout.dialog_settings);
        fillSettings(d);
    }

    private void showSettings() {
        final Dialog d = sheet(R.layout.dialog_settings);
        fillSettings(d);
    }

    private void fillSettings(final Dialog d) {
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setBackgroundDrawableResource(R.drawable.bg_sheet);
        }
        final LinearLayout themeRow = d.findViewById(R.id.themeRow);
        themeRow.removeAllViews();
        for (int i = 0; i < Prefs.THEME_BG.length; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            item.setLayoutParams(lp);

            View circle = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Prefs.THEME_BG[i]);
            g.setStroke((int) dp(2), theme == i ? 0xFF4C6FFF : 0x33909AA8);
            int size = (int) dp(34);
            item.addView(circle, new LinearLayout.LayoutParams(size, size));
            circle.setBackground(g);

            TextView label = new TextView(this);
            label.setText(Prefs.THEME_NAMES[i]);
            label.setTextSize(10.5f);
            label.setGravity(Gravity.CENTER);
            label.setTextColor(theme == i ? 0xFF4C6FFF : 0xFF9AA3B2);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = (int) dp(5);
            item.addView(label, llp);

            item.setOnClickListener(v -> {
                theme = index;
                Prefs.setTheme(this, theme);
                applyTheme(true);
                updateProgressUi();
                d.dismiss();
            });
            themeRow.addView(item);
        }

        final TextView fontView = d.findViewById(R.id.tvFontSize);
        final TextView lineView = d.findViewById(R.id.tvLineSpacing);
        final TextView marginView = d.findViewById(R.id.tvMargin);
        fontView.setText(String.valueOf(fontSize));
        lineView.setText(String.format(Locale.US, "%.2f", lineSpacing / 100f));
        marginView.setText(String.valueOf(margin));

        d.findViewById(R.id.btnFontMinus).setOnClickListener(v -> {
            changeFont(-1);
            fontView.setText(String.valueOf(fontSize));
        });
        d.findViewById(R.id.btnFontPlus).setOnClickListener(v -> {
            changeFont(1);
            fontView.setText(String.valueOf(fontSize));
        });
        d.findViewById(R.id.btnLineMinus).setOnClickListener(v -> {
            lineSpacing = Math.max(110, lineSpacing - 5);
            Prefs.setLineSpacing(this, lineSpacing);
            lineView.setText(String.format(Locale.US, "%.2f", lineSpacing / 100f));
            applyTypography();
        });
        d.findViewById(R.id.btnLinePlus).setOnClickListener(v -> {
            lineSpacing = Math.min(240, lineSpacing + 5);
            Prefs.setLineSpacing(this, lineSpacing);
            lineView.setText(String.format(Locale.US, "%.2f", lineSpacing / 100f));
            applyTypography();
        });
        d.findViewById(R.id.btnMarginMinus).setOnClickListener(v -> {
            margin = Math.max(8, margin - 3);
            Prefs.setMargin(this, margin);
            marginView.setText(String.valueOf(margin));
            applyTypography();
        });
        d.findViewById(R.id.btnMarginPlus).setOnClickListener(v -> {
            margin = Math.min(56, margin + 3);
            Prefs.setMargin(this, margin);
            marginView.setText(String.valueOf(margin));
            applyTypography();
        });

        Switch swScroll = d.findViewById(R.id.swScroll);
        swScroll.setChecked(scrollMode);
        swScroll.setOnCheckedChangeListener((b, checked) -> {
            if (parser != null && parser.isImage() && checked) {
                Toast.makeText(this, "漫画请使用翻页模式", Toast.LENGTH_SHORT).show();
                swScroll.setChecked(false);
                return;
            }
            scrollMode = checked;
            Prefs.setScrollMode(this, checked);
            if (ready) {
                scrollView = null;
                scrollContent = null;
                detachAll();
                goToPage(curPage);
            }
        });

        Switch swVolume = d.findViewById(R.id.swVolume);
        swVolume.setChecked(volumeKeys);
        swVolume.setOnCheckedChangeListener((b, checked) -> {
            volumeKeys = checked;
            Prefs.setVolumeKeys(this, checked);
        });

        Switch swReverse = d.findViewById(R.id.swReverse);
        swReverse.setChecked(reversed);
        swReverse.setOnCheckedChangeListener((b, checked) -> {
            reversed = checked;
            Prefs.setReversed(this, checked);
        });

        Switch swInfo = d.findViewById(R.id.swPageInfo);
        swInfo.setChecked(showPageInfo);
        swInfo.setOnCheckedChangeListener((b, checked) -> {
            showPageInfo = checked;
            Prefs.setPageInfo(this, checked);
            tvPageInfo.setVisibility(checked ? View.VISIBLE : View.GONE);
            updatePageInfo();
        });

        Switch swNight = d.findViewById(R.id.swAutoNight);
        swNight.setChecked(Prefs.autoNight(this));
        swNight.setOnCheckedChangeListener((b, checked) -> Prefs.setAutoNight(this, checked));

        Switch swBold = d.findViewById(R.id.swBold);
        swBold.setChecked(bold);
        swBold.setOnCheckedChangeListener((b, checked) -> {
            bold = checked;
            Prefs.setBold(this, checked);
            if (parser != null && parser.isHtml()) showEpub(curPage, true);
            else if (ready) applyTypography();
        });

        d.show();
    }

    private void changeFont(int delta) {
        fontSize = Math.max(12, Math.min(42, fontSize + delta));
        Prefs.setFontSize(this, fontSize);
        applyTypography();
    }

    private void applyTypography() {
        if (parser == null) return;
        if (parser.isHtml()) {
            showEpub(curPage, true);
            return;
        }
        if (parser.isImage()) return;
        int anchor = parser.offsetForPage(curPage);
        computePageChars();
        int np = parser.reflow(pageChars, anchor);
        if (np >= 0) curPage = np;
        goToPage(curPage);
    }

    // ------------------------------------------------------------------ tts

    private void toggleTts() {
        if (parser == null) return;
        if (parser.isImage() || parser.isHtml()) {
            Toast.makeText(this, "语音朗读暂只支持纯文本图书", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ttsSpeaking) {
            stopTts();
            return;
        }
        if (tts == null) {
            tts = new TextToSpeech(this, status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) {
                    try {
                        tts.setLanguage(Locale.CHINA);
                        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                            @Override public void onStart(String utteranceId) {}
                            @Override public void onDone(String utteranceId) {
                                runOnUiThread(() -> {
                                    if (!ttsSpeaking) return;
                                    if (curPage < parser.pageCount() - 1) {
                                        goToPage(curPage + 1);
                                        speakCurrentPage();
                                    } else {
                                        stopTts();
                                    }
                                });
                            }
                            @Override public void onError(String utteranceId) {
                                runOnUiThread(() -> stopTts());
                            }
                        });
                        speakCurrentPage();
                    } catch (Throwable t) {
                        stopTts();
                    }
                } else {
                    Toast.makeText(this, "设备不支持语音朗读", Toast.LENGTH_SHORT).show();
                }
            });
        } else if (ttsReady) {
            speakCurrentPage();
        } else {
            Toast.makeText(this, "语音引擎还在准备中…", Toast.LENGTH_SHORT).show();
        }
    }

    private void speakCurrentPage() {
        if (tts == null || !ttsReady || parser == null) return;
        String text = parser.text(curPage).toString().replace('\n', ' ').trim();
        if (text.length() > 3500) text = text.substring(0, 3500);
        if (text.isEmpty()) {
            stopTts();
            return;
        }
        ttsSpeaking = true;
        btnTts.setImageResource(R.drawable.ic_pause);
        btnTts.setColorFilter(0xFF4C6FFF);
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "p" + curPage + "_" + System.currentTimeMillis());
        } catch (Throwable t) {
            stopTts();
        }
    }

    private void stopTts() {
        ttsSpeaking = false;
        btnTts.setImageResource(R.drawable.ic_play);
        btnTts.setColorFilter(fg2());
        if (tts != null) {
            try { tts.stop(); } catch (Throwable ignored) {}
        }
    }

    // ------------------------------------------------------------------ small adapter

    private final class SimpleListAdapter extends BaseAdapter {
        private final List<String> titles;
        private final List<String> subs;

        SimpleListAdapter(List<String> titles) {
            this(titles, null);
        }

        SimpleListAdapter(List<String> titles, List<String> subs) {
            this.titles = titles;
            this.subs = subs;
        }

        @Override public int getCount() { return titles.size(); }
        @Override public Object getItem(int position) { return titles.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) v = LayoutInflater.from(ReaderActivity.this).inflate(R.layout.item_list, parent, false);
            TextView t = v.findViewById(R.id.tvItemTitle);
            TextView s = v.findViewById(R.id.tvItemSub);
            t.setText(titles.get(position));
            if (subs != null && position < subs.size()) {
                s.setVisibility(View.VISIBLE);
                s.setText(subs.get(position));
            } else {
                s.setVisibility(View.GONE);
            }
            return v;
        }
    }
}
