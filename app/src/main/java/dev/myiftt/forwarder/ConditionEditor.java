package dev.myiftt.forwarder;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** One row is one phrase; empty rows never broaden a nonempty rule. */
@SuppressLint("ViewConstructor")
final class ConditionEditor extends LinearLayout {
    private static final int INK = Color.rgb(24, 42, 47);
    private static final int TEAL = Color.rgb(0, 105, 92);
    private final LinearLayout rows;
    private final List<Row> conditions = new ArrayList<>();

    ConditionEditor(Context context, String saved) {
        super(context);
        setOrientation(VERTICAL);
        rows = new LinearLayout(context);
        rows.setOrientation(VERTICAL);
        addView(rows, new LayoutParams(-1, -2));
        for (String phrase : saved.split("\\r?\\n")) {
            if (!phrase.trim().isEmpty()) conditions.add(new Row(phrase.trim()));
        }
        while (conditions.size() < 2) conditions.add(new Row(""));
        render();
        Button add = new Button(context);
        add.setText(R.string.add_condition);
        add.setAllCaps(false);
        add.setTextColor(TEAL);
        add.setTextSize(14);
        add.setMinHeight(dp(48));
        add.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(232, 244, 240)));
        add.setOnClickListener(view -> {
            Row row = new Row("");
            conditions.add(row);
            render();
            row.input.requestFocus();
        });
        LayoutParams params = new LayoutParams(-1, -2);
        params.topMargin = dp(8);
        addView(add, params);
    }

    String serialized() {
        StringBuilder result = new StringBuilder();
        for (Row row : conditions) {
            String phrase = row.input.getText().toString().trim();
            if (phrase.isEmpty()) continue;
            if (result.length() > 0) result.append('\n');
            result.append(phrase);
        }
        return result.toString();
    }

    private void render() {
        rows.removeAllViews();
        for (int i = 0; i < conditions.size(); i++) {
            if (i > 0) {
                TextView or = new TextView(getContext());
                or.setText(R.string.condition_or);
                or.setTextSize(11);
                or.setTypeface(null, Typeface.BOLD);
                or.setTextColor(TEAL);
                or.setGravity(Gravity.CENTER);
                or.setPadding(dp(12), dp(4), dp(12), dp(4));
                GradientDrawable pill = new GradientDrawable();
                pill.setColor(Color.rgb(232, 244, 240));
                pill.setCornerRadius(dp(12));
                or.setBackground(pill);
                LayoutParams gap = new LayoutParams(-2, -2);
                gap.gravity = Gravity.CENTER_HORIZONTAL;
                gap.topMargin = dp(5);
                gap.bottomMargin = dp(5);
                rows.addView(or, gap);
            }
            Row row = conditions.get(i);
            row.input.setContentDescription(getContext().getString(R.string.condition_number, i + 1));
            row.remove.setVisibility(conditions.size() > 2 ? VISIBLE : GONE);
            rows.addView(row.line, new LayoutParams(-1, -2));
        }
    }

    private final class Row {
        final LinearLayout line = new LinearLayout(getContext());
        final EditText input = new EditText(getContext());
        final Button remove = new Button(getContext());

        Row(String value) {
            line.setGravity(Gravity.CENTER_VERTICAL);
            input.setSingleLine(true);
            input.setInputType(InputType.TYPE_CLASS_TEXT);
            input.setFilters(new InputFilter[]{(source, start, end, dest, dstart, dend) -> {
                String incoming = source.subSequence(start, end).toString();
                return incoming.indexOf('\n') >= 0 || incoming.indexOf('\r') >= 0
                        ? incoming.replace('\n', ' ').replace('\r', ' ') : null;
            }});
            input.setSaveEnabled(false);
            input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            input.setText(value);
            input.setHint(R.string.condition_hint);
            input.setTextSize(16);
            input.setTextColor(INK);
            input.setHintTextColor(Color.rgb(104, 119, 123));
            input.setMinHeight(dp(52));
            line.addView(input, new LayoutParams(0, -2, 1));
            remove.setText("×");
            remove.setTextSize(23);
            remove.setTextColor(Color.rgb(105, 119, 123));
            remove.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(244, 247, 248)));
            remove.setContentDescription(getContext().getString(R.string.remove_condition));
            remove.setMinimumWidth(0);
            remove.setMinWidth(0);
            remove.setOnClickListener(view -> {
                conditions.remove(this);
                render();
            });
            line.addView(remove, new LayoutParams(dp(48), dp(48)));
        }
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
