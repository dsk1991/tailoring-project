package com.modernmarwar.tailoringstaff;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String API_PREFIX = "tailoring_project.api.";
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final List<JSONObject> definitions = new ArrayList<>();
    private final Map<String, String> measurementValues = new LinkedHashMap<>();
    private final Map<String, EditText> reviewInputs = new LinkedHashMap<>();
    private SecurePrefs securePrefs;
    private ApiClient api;
    private String currentUser = "";
    private String customerId = "";
    private String customerName = "";
    private String templateId = "";
    private String templateLabel = "";
    private String sessionUnit = "Inch";
    private int currentStep;
    private Runnable currentBack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.WHITE);
        window.setNavigationBarColor(Color.WHITE);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        securePrefs = new SecurePrefs(this);
        showConnection();
        String url = securePrefs.getSiteUrl();
        String key = securePrefs.getApiKey();
        String secret = securePrefs.getApiSecret();
        if (!url.isEmpty() && !key.isEmpty() && !secret.isEmpty()) {
            api = new ApiClient(url, key, secret);
            connectAndOpenHome(false);
        }
    }

    private void showConnection() {
        LinearLayout root = screen("Tailoring Staff", "Manual measurement • ERPNext connection", null);
        TextView note = text("Is app mein photo aur AI measurement nahi hai. Staff tape se normal naap lekar step-by-step ERPNext mein save karega.", 15, color("#35506B"));
        note.setBackgroundColor(color("#E8F1FF"));
        note.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(note, matchWrap());
        EditText site = input("https://erp.example.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText key = input("ERPNext API Key", InputType.TYPE_CLASS_TEXT);
        EditText secret = input("ERPNext API Secret", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        site.setText(securePrefs.getSiteUrl());
        key.setText(securePrefs.getApiKey());
        secret.setText(securePrefs.getApiSecret());
        root.addView(field("ERPNext Site URL", site));
        root.addView(field("Staff API Key", key));
        root.addView(field("Staff API Secret", secret));
        Button connect = primaryButton("Test Connection & Continue");
        connect.setOnClickListener(v -> {
            String cleanSite = site.getText().toString().trim();
            String cleanKey = key.getText().toString().trim();
            String cleanSecret = secret.getText().toString().trim();
            if (cleanSite.isEmpty() || cleanKey.isEmpty() || cleanSecret.isEmpty()) {
                toast("Site URL, API Key aur API Secret required hain");
                return;
            }
            api = new ApiClient(cleanSite, cleanKey, cleanSecret);
            runTask("ERPNext connection check ho raha hai…", () -> api.callObject(API_PREFIX + "ping", new JSONObject()), result -> {
                securePrefs.save(cleanSite, cleanKey, cleanSecret);
                currentUser = result.optString("user", "Staff");
                showHome();
            });
        });
        root.addView(connect, buttonParams());
    }

    private void connectAndOpenHome(boolean showConnectionOnFailure) {
        runTask("ERPNext se connect ho raha hai…", () -> api.callObject(API_PREFIX + "ping", new JSONObject()), result -> {
            currentUser = result.optString("user", "Staff");
            showHome();
        }, error -> {
            if (showConnectionOnFailure) showConnection();
            showError(error);
        });
    }

    private void showHome() {
        LinearLayout root = screen("Tailoring Staff", "Logged in: " + currentUser, null);
        TextView banner = text("Garment Measurement", 22, color("#102A43"));
        banner.setTypeface(Typeface.DEFAULT_BOLD);
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(dp(12), dp(22), dp(12), dp(22));
        banner.setBackgroundColor(color("#E8F1FF"));
        root.addView(banner, matchWrap());
        Button start = primaryButton("＋ New Customer Measurement");
        start.setOnClickListener(v -> showCustomerSearch());
        root.addView(start, buttonParams());
        Button recent = secondaryButton("Recent Measurements");
        recent.setOnClickListener(v -> loadRecentSessions());
        root.addView(recent, buttonParams());
        Button settings = secondaryButton("ERPNext Connection Settings");
        settings.setOnClickListener(v -> showConnection());
        root.addView(settings, buttonParams());
        root.addView(sectionTitle("Staff flow"));
        root.addView(flowRow("1", "Customer select karein"));
        root.addView(flowRow("2", "Shirt, Pant, Coat, Koti ya Kurta template select karein"));
        root.addView(flowRow("3", "Purana body naap auto-fill hoga; zarurat par update karein"));
        root.addView(flowRow("4", "Template ke required naap step-by-step lein"));
        root.addView(flowRow("5", "Template record aur Full Body record dono save honge"));
    }

    private void showCustomerSearch() {
        LinearLayout root = screen("Select Customer", "ERPNext Customer search", this::showHome);
        EditText search = input("Name, Customer ID ya mobile", InputType.TYPE_CLASS_TEXT);
        root.addView(search, matchWrap());
        Button find = primaryButton("Search Customer");
        root.addView(find, buttonParams());
        LinearLayout results = vertical();
        root.addView(results, matchWrap());
        find.setOnClickListener(v -> searchCustomers(search.getText().toString(), results));
        searchCustomers("", results);
    }

    private void searchCustomers(String query, LinearLayout results) {
        JSONObject payload = new JSONObject();
        try { payload.put("query", query); payload.put("page_length", 30); } catch (Exception ignored) { }
        runTask("Customers load ho rahe hain…", () -> api.callArray(API_PREFIX + "search_customers", payload), rows -> {
            results.removeAllViews();
            if (rows.length() == 0) { results.addView(emptyState("Koi customer nahi mila")); return; }
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String id = row.optString("name");
                String name = row.optString("customer_name", id);
                String mobile = row.optString("mobile_no", "");
                LinearLayout card = card();
                card.addView(text(name, 18, color("#172B4D")));
                card.addView(text(id + (mobile.isEmpty() ? "" : "  •  " + mobile), 14, color("#5E6C84")));
                Button choose = secondaryButton("Select");
                choose.setOnClickListener(v -> { customerId = id; customerName = name; showTemplateSelection(); });
                card.addView(choose, smallButtonParams());
                results.addView(card, cardParams());
            }
        });
    }

    private void showTemplateSelection() {
        runTask("Garment templates load ho rahe hain…", () -> api.callArray(API_PREFIX + "list_measurement_templates", new JSONObject()), rows -> {
            LinearLayout root = screen("Select Garment", customerName, this::showCustomerSearch);
            if (rows.length() == 0) {
                root.addView(emptyState("ERPNext mein active Garment Measurement Template nahi mila"));
                return;
            }
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String id = row.optString("name");
                String garment = row.optString("garment_type");
                String fit = row.optString("fit_type");
                String label = row.optString("template_name", garment) + "  •  " + fit;
                LinearLayout card = card();
                card.addView(text(label, 18, color("#172B4D")));
                card.addView(text("Version " + row.optInt("template_version", 1), 13, color("#5E6C84")));
                Button choose = primaryButton("Start Measurement");
                choose.setOnClickListener(v -> loadTemplateForm(id, label));
                card.addView(choose, smallButtonParams());
                root.addView(card, cardParams());
            }
        });
    }

    private void loadTemplateForm(String selectedTemplate, String selectedLabel) {
        JSONObject payload = new JSONObject();
        try { payload.put("customer", customerId); payload.put("template", selectedTemplate); } catch (Exception ignored) { }
        runTask("Customer ke purane naap load ho rahe hain…", () -> api.callObject(API_PREFIX + "get_template_measurement_form", payload), result -> {
            templateId = selectedTemplate;
            templateLabel = selectedLabel;
            sessionUnit = result.optString("unit", "Inch");
            definitions.clear();
            measurementValues.clear();
            JSONArray rows = result.optJSONArray("required_measurements");
            if (rows != null) for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                definitions.add(row);
                if (row.optBoolean("has_existing_value")) measurementValues.put(row.optString("measurement_code"), formatNumber(row.optDouble("existing_value")));
            }
            if (definitions.isEmpty()) { showError(new Exception("Is template mein required body measurements nahi mile")); return; }
            currentStep = 0;
            showMeasurementStep();
        });
    }

    private void showMeasurementStep() {
        JSONObject definition = definitions.get(currentStep);
        String code = definition.optString("measurement_code");
        String name = definition.optString("measurement_name", code.replace('_', ' '));
        String section = definition.optString("body_section", "Body");
        LinearLayout root = screen(name, customerName + "  •  " + templateLabel + "  •  " + section, currentStep == 0 ? this::showTemplateSelection : () -> { currentStep--; showMeasurementStep(); });
        TextView progress = text("STEP " + (currentStep + 1) + " OF " + definitions.size(), 13, color("#1769E0"));
        progress.setTypeface(Typeface.DEFAULT_BOLD); progress.setGravity(Gravity.CENTER); progress.setPadding(0, dp(4), 0, dp(8));
        root.addView(progress, matchWrap());
        root.addView(progressBar(currentStep + 1, definitions.size()), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
        MeasurementGuideView diagram = new MeasurementGuideView(this);
        diagram.setMeasurementCode(code);
        LinearLayout.LayoutParams diagramParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300));
        diagramParams.setMargins(0, dp(16), 0, dp(10));
        root.addView(diagram, diagramParams);
        TextView instruction = text(measurementInstruction(code), 15, color("#35506B"));
        instruction.setGravity(Gravity.CENTER); instruction.setPadding(dp(14), dp(12), dp(14), dp(12)); instruction.setBackgroundColor(color("#E8F1FF"));
        root.addView(instruction, matchWrap());
        EditText value = input("Enter measurement", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        value.setText(measurementValues.get(code));
        root.addView(field(name + " (" + sessionUnit + ")", value));
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.CENTER);
        if (currentStep > 0) {
            Button previous = secondaryButton("Previous");
            previous.setOnClickListener(v -> { saveIfPresent(code, value); currentStep--; showMeasurementStep(); });
            actions.addView(previous, halfButtonParams());
        }
        Button next = primaryButton(currentStep == definitions.size() - 1 ? "Review Measurements" : "Save & Next");
        next.setOnClickListener(v -> {
            if (!saveRequired(code, value)) return;
            if (currentStep == definitions.size() - 1) showReview(); else { currentStep++; showMeasurementStep(); }
        });
        actions.addView(next, halfButtonParams());
        root.addView(actions, matchWrap());
    }

    private TextView progressBar(int done, int total) {
        TextView bar = new TextView(this); bar.setBackgroundColor(color("#1769E0"));
        bar.setScaleX(Math.max(0.03f, (float) done / total)); bar.setPivotX(0); return bar;
    }

    private boolean saveRequired(String code, EditText input) {
        try {
            double value = Double.parseDouble(input.getText().toString().trim());
            if (value <= 0) throw new NumberFormatException();
            measurementValues.put(code, formatNumber(value)); return true;
        } catch (Exception error) { toast("Is measurement ki valid value dalein"); return false; }
    }

    private void saveIfPresent(String code, EditText input) {
        String raw = input.getText().toString().trim(); if (raw.isEmpty()) return;
        try { double value = Double.parseDouble(raw); if (value > 0) measurementValues.put(code, formatNumber(value)); } catch (Exception ignored) { }
    }

    private String measurementInstruction(String code) {
        if (code.contains("CHEST")) return "Tape ko chest ke fullest part par, floor ke parallel rakhein.";
        if (code.contains("SHOULDER")) return "Back side par ek shoulder point se doosre shoulder point tak naap lein.";
        if (code.equals("ARM_LENGTH")) return "Shoulder point se halka muda hua haath follow karte hue wrist tak naap lein.";
        if (code.contains("NECK")) return "Neck ke base ke charo taraf tape rakhein; ek finger ki ease rakhein.";
        if (code.contains("BICEP")) return "Upper arm ke sabse mote part ke charo taraf naap lein.";
        if (code.contains("WRIST")) return "Wrist bone ke upar charo taraf tape lagakar naap lein.";
        if (code.contains("BACK")) return "Neck ke base se seedha desired back length point tak naap lein.";
        if (code.contains("WAIST")) return "Natural waist par tape seedhi rakhein; pet ko andar na khinchwayein.";
        if (code.contains("BELLY")) return "Belly ke fullest part ke charo taraf horizontal naap lein.";
        if (code.contains("HIP")) return "Seat/hip ke fullest part ke charo taraf tape seedhi rakhein.";
        if (code.contains("THIGH")) return "Upper thigh ke sabse mote part ke charo taraf naap lein.";
        if (code.contains("KNEE")) return "Knee ke center ke charo taraf comfortable naap lein.";
        if (code.contains("CALF")) return "Calf ke sabse mote part ke charo taraf naap lein.";
        if (code.contains("ANKLE")) return "Ankle ke aas-paas required bottom opening naap lein.";
        if (code.contains("INSEAM")) return "Crotch point se inner leg ke saath ankle tak naap lein.";
        if (code.contains("OUTSEAM")) return "Natural waist se side seam ke saath ankle tak naap lein.";
        if (code.contains("HEIGHT")) return "Bina footwear, floor se head ke top tak seedhi height confirm karein.";
        return "Diagram ke blue tape aur red points ko follow karke normal tailoring measurement lein.";
    }

    private void showReview() {
        LinearLayout root = screen("Review Measurements", customerName + "  •  " + templateLabel, () -> { currentStep = definitions.size() - 1; showMeasurementStep(); });
        root.addView(text("Save par garment template snapshot aur naya consolidated Full Body version dono banenge.", 15, color("#35506B")), matchWrap());
        reviewInputs.clear();
        for (JSONObject definition : definitions) {
            String code = definition.optString("measurement_code");
            String name = definition.optString("measurement_name", code);
            EditText value = input("Required", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            value.setText(measurementValues.get(code));
            root.addView(field(name + " (" + sessionUnit + ")", value));
            reviewInputs.put(code, value);
        }
        Button save = primaryButton("Save in ERPNext"); save.setOnClickListener(v -> saveReviewed()); root.addView(save, buttonParams());
    }

    private void saveReviewed() {
        JSONArray rows = new JSONArray();
        try {
            for (JSONObject definition : definitions) {
                String code = definition.optString("measurement_code");
                EditText input = reviewInputs.get(code);
                double value = Double.parseDouble(input == null ? "" : input.getText().toString().trim());
                if (value <= 0) throw new NumberFormatException();
                JSONObject row = new JSONObject();
                row.put("code", code); row.put("value", value); row.put("unit", sessionUnit);
                row.put("was_existing", definition.optBoolean("has_existing_value")); rows.put(row);
                measurementValues.put(code, formatNumber(value));
            }
        } catch (Exception error) { toast("Sab measurement values valid aur zero se bade hone chahiye"); return; }
        runTask("Template aur Full Body measurement save ho rahe hain…", () -> {
            JSONObject payload = new JSONObject();
            payload.put("customer", customerId);
            payload.put("template", templateId);
            payload.put("measurements", rows.toString());
            payload.put("status", "Reviewed");
            return api.callObject(API_PREFIX + "save_template_measurement", payload);
        }, this::showSaved);
    }

    private void showSaved(JSONObject result) {
        LinearLayout root = screen("Measurement Saved", customerName, this::showHome);
        TextView success = text("✓ ERPNext mein successfully save ho gaya", 22, color("#16845B"));
        success.setTypeface(Typeface.DEFAULT_BOLD); success.setGravity(Gravity.CENTER); success.setPadding(dp(12), dp(30), dp(12), dp(30));
        root.addView(success, matchWrap());
        root.addView(text("Template Record: " + result.optString("template_measurement"), 16, color("#172B4D")), matchWrap());
        root.addView(text("Full Body Version: " + result.optString("body_measurement"), 16, color("#172B4D")), matchWrap());
        root.addView(text(result.optString("garment_type") + "  •  " + result.optString("fit_type") + "  •  " + result.optString("status", "Reviewed"), 16, color("#172B4D")), matchWrap());
        JSONArray calculated = result.optJSONArray("calculated_measurements");
        if (calculated != null && calculated.length() > 0) {
            root.addView(sectionTitle("Calculated Garment Measurements"));
            for (int i = 0; i < calculated.length(); i++) {
                JSONObject item = calculated.optJSONObject(i);
                if (item == null) continue;
                root.addView(text(item.optString("name", item.optString("code")) + ": " + formatNumber(item.optDouble("value")) + " " + item.optString("unit"), 16, color("#172B4D")), matchWrap());
            }
        }
        Button another = primaryButton("New Customer Measurement"); another.setOnClickListener(v -> showCustomerSearch()); root.addView(another, buttonParams());
        Button home = secondaryButton("Back to Home"); home.setOnClickListener(v -> showHome()); root.addView(home, buttonParams());
    }

    private void loadRecentSessions() {
        JSONObject payload = new JSONObject(); try { payload.put("limit", 40); } catch (Exception ignored) { }
        runTask("Recent measurements load ho rahe hain…", () -> api.callArray(API_PREFIX + "list_template_measurements", payload), this::showRecentSessions);
    }

    private void showRecentSessions(JSONArray sessions) {
        LinearLayout root = screen("Recent Measurements", "Latest ERPNext records", this::showHome);
        if (sessions.length() == 0) { root.addView(emptyState("Abhi koi measurement record nahi hai")); return; }
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject item = sessions.optJSONObject(i); if (item == null) continue;
            LinearLayout card = card();
            card.addView(text(item.optString("customer_name", item.optString("customer")), 17, color("#172B4D")));
            card.addView(text(item.optString("garment_type") + "  •  " + item.optString("fit_type") + "\n" + item.optString("name") + "\nBody: " + item.optString("body_measurement") + "\n" + item.optString("measurement_date"), 13, color("#5E6C84")));
            card.addView(statusText(item.optString("status"), "Reviewed".equals(item.optString("status")) ? "#16845B" : "#C56A13"));
            root.addView(card, cardParams());
        }
    }

    private LinearLayout screen(String heading, String subtitle, Runnable back) {
        currentBack = back;
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(color("#F4F7FA"));
        LinearLayout root = vertical(); root.setPadding(dp(16), dp(12), dp(16), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        if (back != null) { Button backButton = secondaryButton("‹ Back"); backButton.setOnClickListener(v -> back.run()); header.addView(backButton, new LinearLayout.LayoutParams(dp(88), dp(44))); }
        LinearLayout headerText = vertical(); TextView title = text(heading, 24, color("#102A43")); title.setTypeface(Typeface.DEFAULT_BOLD); headerText.addView(title);
        if (subtitle != null && !subtitle.isEmpty()) headerText.addView(text(subtitle, 13, color("#5E6C84")));
        LinearLayout.LayoutParams headerTextParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); headerTextParams.setMargins(back == null ? 0 : dp(8), 0, 0, 0);
        header.addView(headerText, headerTextParams); root.addView(header, matchWrap()); spacer(root, 14); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> { view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom()); return insets; });
        scroll.requestApplyInsets(); return root;
    }

    private LinearLayout field(String label, View control) {
        LinearLayout box = vertical(); box.setPadding(0, dp(8), 0, dp(5)); TextView labelView = text(label, 13, color("#35506B")); labelView.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(labelView); box.addView(control, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))); return box;
    }

    private LinearLayout card() { LinearLayout card = vertical(); card.setPadding(dp(14), dp(14), dp(14), dp(14)); card.setBackgroundColor(Color.WHITE); card.setElevation(dp(2)); return card; }
    private LinearLayout flowRow(String number, String label) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); TextView badge = text(number, 16, Color.WHITE); badge.setGravity(Gravity.CENTER); badge.setTypeface(Typeface.DEFAULT_BOLD); badge.setBackgroundColor(color("#1769E0"));
        row.addView(badge, new LinearLayout.LayoutParams(dp(34), dp(34))); TextView labelView = text(label, 15, color("#172B4D")); labelView.setPadding(dp(12), dp(10), 0, dp(10)); row.addView(labelView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); return row;
    }
    private TextView sectionTitle(String value) { TextView view = text(value, 18, color("#102A43")); view.setTypeface(Typeface.DEFAULT_BOLD); view.setPadding(0, dp(18), 0, dp(8)); return view; }
    private TextView statusText(String value, String valueColor) { TextView view = text(value, 14, color(valueColor)); view.setTypeface(Typeface.DEFAULT_BOLD); view.setPadding(0, dp(8), 0, dp(5)); return view; }
    private TextView emptyState(String value) { TextView view = text(value, 16, color("#5E6C84")); view.setGravity(Gravity.CENTER); view.setPadding(dp(12), dp(35), dp(12), dp(35)); return view; }
    private EditText input(String hint, int inputType) { EditText input = new EditText(this); input.setHint(hint); input.setTextSize(16); input.setSingleLine(true); input.setInputType(inputType); input.setPadding(dp(12), 0, dp(12), 0); input.setBackgroundColor(Color.WHITE); return input; }
    private Button primaryButton(String label) { return styledButton(label, "#1769E0", "#FFFFFF"); }
    private Button secondaryButton(String label) { return styledButton(label, "#E8F1FF", "#174EA6"); }
    private Button styledButton(String label, String background, String foreground) { Button button = new Button(this); button.setText(label); button.setTextSize(15); button.setTextColor(color(foreground)); button.setAllCaps(false); button.setTypeface(Typeface.DEFAULT_BOLD); button.setBackgroundColor(color(background)); return button; }
    private TextView text(String value, int size, int textColor) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(textColor); view.setLineSpacing(0, 1.12f); return view; }
    private LinearLayout vertical() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams cardParams() { LinearLayout.LayoutParams p = matchWrap(); p.setMargins(0, dp(7), 0, dp(7)); return p; }
    private LinearLayout.LayoutParams buttonParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)); p.setMargins(0, dp(8), 0, dp(5)); return p; }
    private LinearLayout.LayoutParams smallButtonParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)); p.setMargins(0, dp(8), 0, 0); return p; }
    private LinearLayout.LayoutParams halfButtonParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(54), 1f); p.setMargins(dp(3), dp(8), dp(3), dp(5)); return p; }
    private void spacer(LinearLayout root, int height) { SpaceView space = new SpaceView(this); root.addView(space, new LinearLayout.LayoutParams(1, dp(height))); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int color(String value) { return Color.parseColor(value); }
    private String formatNumber(double value) { return Math.abs(value - Math.rint(value)) < 0.001 ? String.valueOf((long) value) : String.format(Locale.US, "%.2f", value); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }

    @Override public void onBackPressed() { if (currentBack != null) currentBack.run(); else super.onBackPressed(); }
    private <T> void runTask(String message, Callable<T> work, ResultHandler<T> success) { runTask(message, work, success, this::showError); }
    private <T> void runTask(String message, Callable<T> work, ResultHandler<T> success, ErrorHandler failure) {
        ProgressDialog progress = new ProgressDialog(this); progress.setMessage(message); progress.setCancelable(false); progress.show();
        executor.submit(() -> { try { T result = work.call(); runOnUiThread(() -> { progress.dismiss(); success.accept(result); }); } catch (Exception error) { runOnUiThread(() -> { progress.dismiss(); failure.accept(error); }); } });
    }
    private void showError(Exception error) { String message = error.getMessage(); if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName(); new AlertDialog.Builder(this).setTitle("Action failed").setMessage(message).setPositiveButton("OK", null).show(); }
    @Override protected void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
    private interface ResultHandler<T> { void accept(T value); }
    private interface ErrorHandler { void accept(Exception error); }
    private static final class SpaceView extends View { SpaceView(Activity activity) { super(activity); } }
}
