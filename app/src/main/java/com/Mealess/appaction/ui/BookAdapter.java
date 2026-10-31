package com.Mealess.appaction.ui;

import android.content.Context;
import android.util.LruCache;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.Mealess.appaction.Book;
import com.Mealess.appaction.Covers;
import com.Mealess.appaction.R;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Recycled bookshelf list with an LRU cover cache and off-main-thread cover decoding. */
public class BookAdapter extends BaseAdapter {

    public interface Listener {
        void onOpen(Book book);
        void onToggleFavorite(Book book);
        void onLongPress(Book book);
    }

    private static final class Holder {
        ImageView cover;
        TextView coverText;
        TextView name;
        TextView meta;
        TextView percent;
        TextView format;
        ProgressBar progress;
        ImageView star;
    }

    private final Context context;
    private final List<Book> items = new ArrayList<>();
    private final LruCache<String, Bitmap> cache;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;

    public BookAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        int mem = (int) (Runtime.getRuntime().maxMemory() / 1024);
        cache = new LruCache<String, Bitmap>(Math.max(4096, mem / 10)) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value == null ? 0 : value.getByteCount() / 1024;
            }
        };
    }

    public void setItems(List<Book> books) {
        items.clear();
        if (books != null) items.addAll(books);
        notifyDataSetChanged();
    }

    public List<Book> items() {
        return items;
    }

    @Override public int getCount() { return items.size(); }
    @Override public Book getItem(int position) { return items.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder h;
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_book, parent, false);
            h = new Holder();
            h.cover = convertView.findViewById(R.id.ivCover);
            h.coverText = convertView.findViewById(R.id.tvCoverText);
            h.name = convertView.findViewById(R.id.tvName);
            h.meta = convertView.findViewById(R.id.tvMeta);
            h.percent = convertView.findViewById(R.id.tvPercent);
            h.progress = convertView.findViewById(R.id.pbProgress);
            h.star = convertView.findViewById(R.id.btnStar);
            convertView.setTag(h);
        } else {
            h = (Holder) convertView.getTag();
        }

        final Book b = items.get(position);
        h.name.setText(b.name == null ? "" : b.name);

        StringBuilder meta = new StringBuilder();
        if (b.author != null && !b.author.trim().isEmpty()) {
            meta.append(b.author.trim());
        } else {
            meta.append(b.displayFormat());
        }
        meta.append("  ·  ").append(b.displayFormat());
        int minutes = (int) (b.totalReadTime / 60000L);
        if (minutes >= 60) meta.append("  ·  已读 ").append(minutes / 60).append(" 小时");
        else if (minutes > 0) meta.append("  ·  已读 ").append(minutes).append(" 分钟");
        h.meta.setText(meta.toString());

        int percent = estimatePercent(b);
        h.progress.setProgress(percent);
        h.percent.setText(percent <= 0 ? "未读" : percent + "%");

        h.star.setImageResource(b.favorite ? R.drawable.ic_star : R.drawable.ic_star_line);
        h.star.setColorFilter(b.favorite ? 0xFFF0A020 : 0xFFADB5C2);
        h.star.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { listener.onToggleFavorite(b); }
        });

        bindCover(h, b);

        convertView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { listener.onOpen(b); }
        });
        convertView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                listener.onLongPress(b);
                return true;
            }
        });
        return convertView;
    }

    private static int estimatePercent(Book b) {
        int p = Math.round(Math.max(0f, Math.min(1f, b.progress)) * 100f);
        if (p == 0 && b.lastPage > 0) p = 1;
        return p;
    }

    private void bindCover(final Holder h, final Book b) {
        final String key = b.uri == null ? "" : b.uri;
        Bitmap cached = cache.get(key);
        if (cached != null && !cached.isRecycled()) {
            h.cover.setImageBitmap(cached);
            h.coverText.setVisibility(View.GONE);
            return;
        }
        h.cover.setImageDrawable(null);
        h.cover.setBackgroundResource(R.drawable.bg_cover);
        h.coverText.setVisibility(View.VISIBLE);
        h.coverText.setTextColor(0x8A6B7480);
        h.coverText.setText(shortTitle(b.name));
        h.cover.setTag(key);

        pool.execute(new Runnable() {
            @Override public void run() {
                Bitmap bmp = null;
                try {
                    if (!Covers.hasCover(context, b)) Covers.ensure(context, b);
                    bmp = Covers.load(Covers.fileFor(context, b), 200, 280);
                } catch (Throwable ignored) {}
                if (bmp == null) return;
                final Bitmap result = bmp;
                cache.put(key, result);
                main.post(new Runnable() {
                    @Override public void run() {
                        if (key.equals(h.cover.getTag()) && !result.isRecycled()) {
                            h.cover.setImageBitmap(result);
                            h.coverText.setVisibility(View.GONE);
                        }
                    }
                });
            }
        });
    }

    private static String shortTitle(String name) {
        if (name == null) return "";
        String n = name.trim();
        return n.length() > 6 ? n.substring(0, 6) : n;
    }

    public void shutdown() {
        pool.shutdownNow();
        cache.evictAll();
    }
}
