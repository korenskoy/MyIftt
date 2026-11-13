package dev.myiftt.forwarder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int SMS_PERMISSION_REQUEST = 1;
    private static final int INK = Color.rgb(24, 42, 47);
    private static final int MUTED = Color.rgb(78, 96, 103);
    private static final int ACCENT = Color.rgb(0, 105, 92);
    private static final int ERROR = Color.rgb(159, 39, 39);

    private Switch enabled;
    private EditText token;
    private EditText chatId;
    private Spinner bankApp;
    private ConditionEditor bankFilter;
    private EditText smsSender;
    private ConditionEditor smsFilter;
    private TextView notificationState;
    private TextView smsState;
    private TextView deliveryState;
    private TextView queueState;
    private TextView feedback;
    private Button findChatsButton;
    private Button clearQueueButton;
    private LinearLayout telegramFields;
    private Button telegramToggle;
    private final ExecutorService setupExecutor = Executors.newSingleThreadExecutor();
    private final List<String> bankPackages = new ArrayList<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener statusListener =
            (preferences, key) -> {
                if ("status".equals(key)) runOnUiThread(this::refreshStatus);
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(16), dp(20), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(scroll);

        TextView title = text(page, R.string.app_name, 30, INK);
        title.setTypeface(null, Typeface.BOLD);
        text(page, R.string.app_description, 15, MUTED);

        LinearLayout control = card(page);
        enabled = new Switch(this);
        enabled.setText(R.string.forwarding);
        enabled.setTextColor(INK);
        enabled.setTextSize(18);
        enabled.setMinHeight(dp(52));
        enabled.setChecked(dev.myiftt.forwarder.Settings.isEnabled(this));
        control.addView(enabled, new LinearLayout.LayoutParams(-1, -2));
        help(control, R.string.forwarding_help);
        button(control, R.string.open_log, false,
                view -> startActivity(new Intent(this, LogActivity.class)));

        LinearLayout telegram = section(page, R.string.telegram_section);
        telegramToggle = button(telegram, R.string.telegram_edit, false, view -> toggleTelegram());
        telegramFields = new LinearLayout(this);
        telegramFields.setOrientation(LinearLayout.VERTICAL);
        telegram.addView(telegramFields, new LinearLayout.LayoutParams(-1, -2));
        token = field(telegramFields, R.string.bot_token, R.string.token_hint,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setSaveEnabled(false);
        token.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        token.setText(dev.myiftt.forwarder.Settings.get(this, "bot_token"));
        chatId = field(telegramFields, R.string.chat_id, R.string.chat_hint,
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        chatId.setText(dev.myiftt.forwarder.Settings.get(this, "chat_id"));
        help(telegramFields, R.string.telegram_help);
        findChatsButton = button(telegramFields, R.string.find_chat, false, view -> findChats());
        button(telegramFields, R.string.test_message, false, view -> saveAndTest());
        help(telegramFields, R.string.test_help);
        setTelegramExpanded(token.getText().length() == 0 || chatId.getText().length() == 0);

        LinearLayout bank = section(page, R.string.bank_section);
        TextView bankLabel = label(bank, R.string.bank_app);
        bankApp = new Spinner(this);
        bankApp.setId(View.generateViewId());
        bankLabel.setLabelFor(bankApp.getId());
        bankApp.setMinimumHeight(dp(52));
        bank.addView(bankApp, new LinearLayout.LayoutParams(-1, -2));
        populateBankApps();
        label(bank, R.string.bank_keyword);
        bankFilter = new ConditionEditor(this, savedInstanceState == null
                ? dev.myiftt.forwarder.Settings.get(this, "bank_filter")
                : savedInstanceState.getString("bank_conditions", ""));
        bank.addView(bankFilter, new LinearLayout.LayoutParams(-1, -2));
        help(bank, R.string.bank_help);
        notificationState = text(bank, R.string.notification_denied, 14, MUTED);
        button(bank, R.string.notification_permission, false,
                view -> openSettings(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        help(bank, R.string.notification_help);

        LinearLayout sms = section(page, R.string.sms_section);
        smsSender = field(sms, R.string.sms_sender, R.string.sms_sender_hint, InputType.TYPE_CLASS_TEXT);
        smsSender.setText(dev.myiftt.forwarder.Settings.get(this, "sms_sender"));
        label(sms, R.string.sms_keyword);
        smsFilter = new ConditionEditor(this, savedInstanceState == null
                ? dev.myiftt.forwarder.Settings.get(this, "sms_filter")
                : savedInstanceState.getString("sms_conditions", ""));
        sms.addView(smsFilter, new LinearLayout.LayoutParams(-1, -2));
        help(sms, R.string.sms_help);
        help(sms, R.string.sms_privacy);
        smsState = text(sms, R.string.sms_denied, 14, MUTED);
        button(sms, R.string.sms_permission, false, view -> requestSmsPermission());
        page.removeView(telegram);
        page.addView(telegram);

        LinearLayout status = section(page, R.string.status_section);
        deliveryState = text(status, R.string.no_deliveries, 15, INK);
        deliveryState.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        queueState = text(status, R.string.no_deliveries, 14, MUTED);
        clearQueueButton = button(status, R.string.clear_queue, false, view -> confirmClearQueue());
        clearQueueButton.setTextColor(ERROR);
        feedback = text(page, R.string.saved_off, 15, ACCENT);
        feedback.setVisibility(View.GONE);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        button(page, R.string.save, true, view -> saveSettings());
        if (savedInstanceState != null) {
            enabled.setChecked(savedInstanceState.getBoolean("forwarding_enabled", enabled.isChecked()));
            smsSender.setText(savedInstanceState.getString("sms_sender", smsSender.getText().toString()));
            int selected = bankPackages.indexOf(savedInstanceState.getString("bank_package", selectedBankPackage()));
            if (selected >= 0) bankApp.setSelection(selected);
        }
        refreshStatus();
    }

    private void toggleTelegram() {
        setTelegramExpanded(telegramFields.getVisibility() != View.VISIBLE);
    }

    private void setTelegramExpanded(boolean expanded) {
        telegramFields.setVisibility(expanded ? View.VISIBLE : View.GONE);
        telegramToggle.setText(expanded ? R.string.telegram_collapse : R.string.telegram_edit);
    }

    private void populateBankApps() {
        PackageManager manager = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities = manager.queryIntentActivities(launcher, 0);
        Map<String, String> apps = new HashMap<>();
        for (ResolveInfo activity : activities) {
            String packageName = activity.activityInfo.packageName;
            if (!packageName.equals(getPackageName())) {
                apps.put(packageName, activity.loadLabel(manager).toString());
            }
        }
        List<Map.Entry<String, String>> sorted = new ArrayList<>(apps.entrySet());
        Collator collator = Collator.getInstance();
        sorted.sort((left, right) -> collator.compare(left.getValue(), right.getValue()));
        List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.no_app));
        bankPackages.add("");
        String saved = dev.myiftt.forwarder.Settings.get(this, "bank_package");
        int selected = 0;
        for (Map.Entry<String, String> app : sorted) {
            bankPackages.add(app.getKey());
            labels.add(app.getValue());
            if (app.getKey().equals(saved) || (saved.isEmpty()
                    && (app.getKey().equals("ru.ftc.faktura.nskbl")
                    || app.getValue().equalsIgnoreCase("BL-Online")))) {
                selected = bankPackages.size() - 1;
            }
        }
        if (!saved.isEmpty() && !bankPackages.contains(saved)) {
            bankPackages.add(saved);
            labels.add(getString(R.string.missing_app));
            selected = bankPackages.size() - 1;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        bankApp.setAdapter(adapter);
        bankApp.setSelection(selected);
    }

    private String selectedBankPackage() {
        int selected = bankApp.getSelectedItemPosition();
        return selected >= 0 && selected < bankPackages.size() ? bankPackages.get(selected) : "";
    }

    private boolean validateToken() {
        if (!token.getText().toString().trim().matches("[0-9]+:[A-Za-z0-9_-]{20,}")) {
            setTelegramExpanded(true);
            token.setError(getString(R.string.token_required));
            token.requestFocus();
            return false;
        }
        return true;
    }

    private boolean validateCredentials() {
        if (!validateToken()) return false;
        if (!chatId.getText().toString().trim().matches("-?[0-9]+")) {
            setTelegramExpanded(true);
            chatId.setError(getString(R.string.chat_required));
            chatId.requestFocus();
            return false;
        }
        return true;
    }

    private void findChats() {
        if (!validateToken()) return;
        findChatsButton.setEnabled(false);
        String currentToken = token.getText().toString().trim();
        setupExecutor.execute(() -> {
            try {
                List<TelegramSetup.Chat> chats = TelegramSetup.findChats(currentToken);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    findChatsButton.setEnabled(true);
                    if (chats.isEmpty()) {
                        new AlertDialog.Builder(this).setTitle(R.string.find_chat)
                                .setMessage(R.string.no_chats_help)
                                .setPositiveButton(android.R.string.ok, null).show();
                        return;
                    }
                    String[] titles = new String[chats.size()];
                    for (int i = 0; i < chats.size(); i++) titles[i] = chats.get(i).title();
                    new AlertDialog.Builder(this).setTitle(R.string.select_chat)
                            .setItems(titles, (dialog, which) -> chatId.setText(chats.get(which).id()))
                            .setNegativeButton(android.R.string.cancel, null).show();
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    findChatsButton.setEnabled(true);
                    showFeedback(R.string.chat_lookup_failed, true);
                });
            }
        });
    }

    private boolean saveFields(boolean test) {
        if ((enabled.isChecked() || test) && !validateCredentials()) return false;
        if (enabled.isChecked()) {
            boolean hasSmsFilter = !smsSender.getText().toString().trim().isEmpty()
                    || !smsFilter.serialized().isEmpty();
            if (selectedBankPackage().isEmpty() && !hasSmsFilter) {
                showFeedback(R.string.source_required, true);
                return false;
            }
            if (!selectedBankPackage().isEmpty() && !hasNotificationAccess()) {
                showFeedback(R.string.notification_required, true);
                return false;
            }
            if (hasSmsFilter && !hasSmsPermission()) {
                showFeedback(R.string.sms_required, true);
                return false;
            }
        }
        try {
            dev.myiftt.forwarder.Settings.save(this, enabled.isChecked(),
                    token.getText().toString().trim(), chatId.getText().toString().trim(),
                    selectedBankPackage(), bankFilter.serialized(),
                    smsSender.getText().toString().trim(), smsFilter.serialized());
            refreshStatus();
            return true;
        } catch (Exception exception) {
            showFeedback(R.string.save_failed, true);
            return false;
        }
    }

    private void saveSettings() {
        if (saveFields(false)) {
            showFeedback(enabled.isChecked() ? R.string.saved_on : R.string.saved_off, false);
        }
    }

    private void saveAndTest() {
        if (!saveFields(true)) return;
        try {
            ForwardJob.enqueueTest(this);
            showFeedback(R.string.test_queued, false);
            refreshStatus();
        } catch (Exception exception) {
            showFeedback(R.string.test_failed, true);
        }
    }

    private boolean hasSmsPermission() {
        return checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotificationAccess() {
        ComponentName component = new ComponentName(this, ForwardNotificationService.class);
        if (Build.VERSION.SDK_INT >= 27) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            return manager != null && manager.isNotificationListenerAccessGranted(component);
        }
        String listeners = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (listeners == null) return false;
        for (String listener : listeners.split(":")) {
            if (component.equals(ComponentName.unflattenFromString(listener))) return true;
        }
        return false;
    }

    private void requestSmsPermission() {
        if (hasSmsPermission()) {
            refreshStatus();
            return;
        }
        SharedPreferences preferences = dev.myiftt.forwarder.Settings.prefs(this);
        if (preferences.getBoolean("sms_permission_requested", false)
                && !shouldShowRequestPermissionRationale(Manifest.permission.RECEIVE_SMS)) {
            openSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
            return;
        }
        preferences.edit().putBoolean("sms_permission_requested", true).apply();
        requestPermissions(new String[]{Manifest.permission.RECEIVE_SMS}, SMS_PERMISSION_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshStatus();
        if (requestCode == SMS_PERMISSION_REQUEST && !hasSmsPermission()) {
            showFeedback(R.string.sms_denied_help, true);
        }
    }

    private void openSettings(Intent intent) {
        try {
            startActivity(intent);
        } catch (RuntimeException exception) {
            showFeedback(R.string.settings_unavailable, true);
        }
    }

    private void refreshStatus() {
        if (notificationState == null) return;
        boolean notifications = hasNotificationAccess();
        notificationState.setText(notifications ? R.string.notification_granted : R.string.notification_denied);
        notificationState.setTextColor(notifications ? ACCENT : MUTED);
        boolean sms = hasSmsPermission();
        smsState.setText(sms ? R.string.sms_granted : R.string.sms_denied);
        smsState.setTextColor(sms ? ACCENT : MUTED);
        deliveryState.setText(dev.myiftt.forwarder.Settings.prefs(this)
                .getString("status", getString(R.string.no_deliveries)));
        try {
            int count = ForwardJob.pendingCount(this);
            queueState.setText(getString(R.string.queue_count, count));
            clearQueueButton.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        } catch (RuntimeException exception) {
            queueState.setText(R.string.queue_unavailable);
            clearQueueButton.setVisibility(View.GONE);
        }
    }

    private void confirmClearQueue() {
        new AlertDialog.Builder(this).setTitle(R.string.clear_queue)
                .setMessage(R.string.clear_queue_confirmation)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    try {
                        ForwardJob.clearQueue(this);
                        refreshStatus();
                        showFeedback(R.string.queue_cleared, false);
                    } catch (Exception exception) {
                        showFeedback(R.string.clear_queue_failed, true);
                    }
                }).show();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("bank_conditions", bankFilter.serialized());
        state.putString("sms_conditions", smsFilter.serialized());
        state.putString("sms_sender", smsSender.getText().toString());
        state.putString("bank_package", selectedBankPackage());
        state.putBoolean("forwarding_enabled", enabled.isChecked());
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onResume() {
        super.onResume();
        dev.myiftt.forwarder.Settings.prefs(this).registerOnSharedPreferenceChangeListener(statusListener);
        ForwardJob.schedule(this);
        refreshStatus();
    }

    @Override
    protected void onPause() {
        dev.myiftt.forwarder.Settings.prefs(this).unregisterOnSharedPreferenceChangeListener(statusListener);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        setupExecutor.shutdownNow();
        super.onDestroy();
    }

    private void showFeedback(int message, boolean error) {
        feedback.setText(message);
        feedback.setTextColor(error ? ERROR : ACCENT);
        feedback.setVisibility(View.VISIBLE);
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(18));
        card.setBackground(background);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(18);
        parent.addView(card, layout);
        return card;
    }

    private LinearLayout section(LinearLayout parent, int title) {
        LinearLayout section = card(parent);
        TextView heading = text(section, title, 19, INK);
        heading.setTypeface(null, Typeface.BOLD);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        return section;
    }

    private TextView text(LinearLayout parent, int content, int size, int color) {
        TextView text = new TextView(this);
        text.setText(content);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setLineSpacing(dp(2), 1);
        text.setPadding(0, dp(5), 0, dp(5));
        parent.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return text;
    }

    private void help(LinearLayout parent, int content) {
        text(parent, content, 13, MUTED);
    }

    private TextView label(LinearLayout parent, int content) {
        TextView label = text(parent, content, 14, INK);
        label.setPadding(0, dp(14), 0, 0);
        return label;
    }

    private EditText field(LinearLayout parent, int labelText, int hint, int type) {
        TextView label = label(parent, labelText);
        EditText input = new EditText(this);
        input.setId(View.generateViewId());
        input.setHint(hint);
        input.setInputType(type);
        input.setSingleLine(true);
        input.setTextSize(16);
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setMinHeight(dp(52));
        label.setLabelFor(input.getId());
        parent.addView(input, new LinearLayout.LayoutParams(-1, -2));
        return input;
    }

    private Button button(LinearLayout parent, int title, boolean primary, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setMinHeight(dp(52));
        button.setTextColor(primary ? Color.WHITE : ACCENT);
        button.setBackgroundTintList(ColorStateList.valueOf(primary ? ACCENT : Color.rgb(224, 239, 234)));
        button.setOnClickListener(action);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(8);
        parent.addView(button, layout);
        return button;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
