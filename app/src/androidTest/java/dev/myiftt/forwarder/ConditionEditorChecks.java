package dev.myiftt.forwarder;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.List;

/** Must run on the Android main thread; never saves app settings. */
final class ConditionEditorChecks {
    private ConditionEditorChecks() {}

    static void run(Context context) {
        ConditionEditor editor = new ConditionEditor(context, "RUR");
        require(fields(editor).size() == 2, "At least two phrase rows");
        require(editor.serialized().equals("RUR"), "Blank optional row must not broaden rule");
        fields(editor).get(1).setText("  USD  ");
        require(editor.serialized().equals("RUR\nUSD"), "Two trimmed alternative phrases");
        button(editor, context.getString(R.string.add_condition)).performClick();
        require(fields(editor).size() == 3, "Add condition");
        fields(editor).get(2).setText("EUR");
        List<View> removals = described(editor, context.getString(R.string.remove_condition));
        removals.get(2).performClick();
        require(editor.serialized().equals("RUR\nUSD"), "Removing third condition retains existing input");
        fields(editor).get(0).setText("one\ntwo");
        require(editor.serialized().equals("one two\nUSD"), "Pasted line break stays in one phrase");
        fields(editor).get(0).setText("  ");
        fields(editor).get(1).setText("");
        require(editor.serialized().isEmpty(), "All empty rows serialize as empty");
        ConditionEditor restored = new ConditionEditor(context, "RUR\n\nUSD");
        require(restored.serialized().equals("RUR\nUSD"), "Restore OR conditions without blank clauses");
    }

    private static List<EditText> fields(View view) {
        List<EditText> result = new ArrayList<>();
        if (view instanceof EditText) result.add((EditText) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) result.addAll(fields(group.getChildAt(i)));
        }
        return result;
    }

    private static Button button(View view, String text) {
        if (view instanceof Button && ((Button) view).getText().toString().equals(text)) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = button(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<View> described(View view, String description) {
        List<View> result = new ArrayList<>();
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()))
            result.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) result.addAll(described(group.getChildAt(i), description));
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
