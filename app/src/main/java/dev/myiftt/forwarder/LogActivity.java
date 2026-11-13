package dev.myiftt.forwarder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LogActivity extends Activity {
    private static final int INK = Color.rgb(24, 42, 47);
    private static final int MUTED = Color.rgb(78, 96, 103);
    private static final int TEAL = Color.rgb(0, 105, 92);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<EventLog.Entry> entries = new ArrayList<>();
    private final EventAdapter adapter = new EventAdapter();
    private TextView count;
    private TextView emptyTitle;
    private TextView emptyHelp;
    private LinearLayout empty;
    private Button refresh;
    private Button clear;
    private ListView list;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(12), dp(20), dp(12));
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(dp(20) + bars.left, dp(12) + bars.top,
                        dp(20) + bars.right, dp(12) + bars.bottom);
            } else {
                view.setPadding(dp(20) + insets.getSystemWindowInsetLeft(), dp(12) + insets.getSystemWindowInsetTop(),
                        dp(20) + insets.getSystemWindowInsetRight(), dp(12) + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(page);
        Button back = button(R.string.log_back);
        back.setOnClickListener(view -> finish());
        page.addView(back, new LinearLayout.LayoutParams(-2, -2));
        TextView title = text(28, INK);
        title.setText(R.string.log_title);
        title.setTypeface(null, Typeface.BOLD);
        page.addView(title);
        TextView subtitle = text(13, MUTED);
        subtitle.setText(R.string.log_subtitle);
        page.addView(subtitle);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(android.view.Gravity.CENTER_VERTICAL);
        count = text(13, MUTED);
        count.setText(R.string.log_loading);
        count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        actions.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
        refresh = button(R.string.log_refresh);
        refresh.setOnClickListener(view -> loadEvents());
        actions.addView(refresh, new LinearLayout.LayoutParams(-2, -2));
        clear = button(R.string.log_clear);
        clear.setTextColor(Color.rgb(159, 39, 39));
        clear.setOnClickListener(view -> confirmClear());
        actions.addView(clear, new LinearLayout.LayoutParams(-2, -2));
        page.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setPadding(dp(20), dp(28), dp(20), dp(28));
        empty.setBackground(background(Color.WHITE));
        emptyTitle = text(20, INK);
        emptyTitle.setTypeface(null, Typeface.BOLD);
        emptyTitle.setText(R.string.log_empty_title);
        empty.addView(emptyTitle);
        emptyHelp = text(15, MUTED);
        emptyHelp.setText(R.string.log_empty_help);
        empty.addView(emptyHelp);
        page.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(dp(10));
        list.setClipToPadding(false);
        list.setAdapter(adapter);
        list.setSelector(android.R.color.transparent);
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    @Override protected void onResume() {
        super.onResume();
        loadEvents();
    }

    private void loadEvents() {
        refresh.setEnabled(false);
        clear.setEnabled(false);
        worker.execute(() -> {
            try {
                List<EventLog.Entry> loaded = EventLog.recent(this);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    entries.clear();
                    entries.addAll(loaded);
                    adapter.notifyDataSetChanged();
                    count.setText(getString(R.string.log_count, entries.size()));
                    emptyTitle.setText(R.string.log_empty_title);
                    emptyHelp.setText(R.string.log_empty_help);
                    empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
                    list.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
                    refresh.setEnabled(true);
                    clear.setEnabled(!entries.isEmpty());
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    count.setText(R.string.log_error);
                    refresh.setEnabled(true);
                    clear.setEnabled(true);
                });
            }
        });
    }

    private void confirmClear() {
        new AlertDialog.Builder(this).setTitle(R.string.log_clear)
                .setMessage(R.string.log_clear_confirmation)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    clear.setEnabled(false);
                    refresh.setEnabled(false);
                    worker.execute(() -> {
                        try {
                            EventLog.clear(this);
                            runOnUiThread(() -> {
                                if (!isFinishing() && !isDestroyed()) loadEvents();
                            });
                        } catch (Exception error) {
                            runOnUiThread(() -> {
                                if (isFinishing() || isDestroyed()) return;
                                count.setText(R.string.log_clear_error);
                                clear.setEnabled(true);
                                refresh.setEnabled(true);
                            });
                        }
                    });
                }).show();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private final class EventAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public EventLog.Entry getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean isEnabled(int position) { return false; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            EventRow row = recycled == null ? new EventRow() : (EventRow) recycled.getTag();
            EventLog.Entry event = getItem(position);
            row.source.setText(event.source);
            row.time.setText(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                    .format(new Date(event.time)));
            row.outcome.setText(event.outcome);
            String outcome = event.outcome.toLowerCase(Locale.ROOT);
            boolean failed = outcome.contains("ошиб") || outcome.contains("отклон");
            boolean skipped = outcome.contains("пропущ") || outcome.contains("дублик");
            row.outcome.setTextColor(failed ? Color.rgb(159, 39, 39) : skipped ? MUTED : TEAL);
            row.outcome.setBackground(background(failed ? Color.rgb(255, 236, 232)
                    : skipped ? Color.rgb(238, 241, 243) : Color.rgb(228, 244, 236)));
            row.reason.setText(event.reason);
            row.preview.setText(event.preview);
            row.preview.setVisibility(event.preview.isEmpty() ? View.GONE : View.VISIBLE);
            return row.card;
        }
    }

    private final class EventRow {
        final LinearLayout card = new LinearLayout(LogActivity.this);
        final TextView source = text(16, INK);
        final TextView time = text(12, MUTED);
        final TextView outcome = text(12, TEAL);
        final TextView reason = text(14, INK);
        final TextView preview = text(14, MUTED);

        EventRow() {
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(12), dp(16), dp(14));
            card.setBackground(background(Color.WHITE));
            source.setTypeface(null, Typeface.BOLD);
            card.addView(source);
            card.addView(time);
            outcome.setTypeface(null, Typeface.BOLD);
            outcome.setPadding(dp(10), dp(5), dp(10), dp(5));
            LinearLayout.LayoutParams chip = new LinearLayout.LayoutParams(-2, -2);
            chip.topMargin = dp(6);
            chip.bottomMargin = dp(5);
            card.addView(outcome, chip);
            card.addView(reason);
            preview.setMaxLines(6);
            preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(preview);
            card.setTag(this);
        }
    }

    private TextView text(int size, int color) {
        TextView view = new TextView(this);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(2), 1);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private Button button(int label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(TEAL);
        button.setMinHeight(dp(48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(232, 244, 240)));
        return button;
    }

    private GradientDrawable background(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(16));
        return background;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
