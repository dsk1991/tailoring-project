package com.modernmarwar.tailoringstaff;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int CAMERA_PERMISSION = 700;
    private static final int FRONT_REQUEST = 701;
    private static final int SIDE_REQUEST = 702;
    private static final int BACK_REQUEST = 703;
    private static final String API_PREFIX = "tailoring_project.api.";

    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Map<String, File> photos = new LinkedHashMap<>();
    private final Map<String, EditText> measurementInputs = new LinkedHashMap<>();
    private final Map<String, JSONObject> measurementMeta = new LinkedHashMap<>();

    private SecurePrefs securePrefs;
    private ApiClient api;
    private String currentUser = "";
    private String customerId = "";
    private String customerName = "";
    private String sessionName = "";
    private String sessionUnit = "Inch";
    private String pendingPhotoType = "";
    private Runnable currentBack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.WHITE);
        window.setNavigationBarColor(Color.WHITE);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        securePrefs = new SecurePrefs(this);
        String url = securePrefs.getSiteUrl();
        String key = securePrefs.getApiKey();
        String secret = securePrefs.getApiSecret();
        showConnection();
        if (!url.isEmpty() && !key.isEmpty() && !secret.isEmpty()) {
            api = new ApiClient(url, key, secret);
            connectAndOpenHome(false);
        }
    }

    private void showConnection() {
        LinearLayout root = screen("Tailoring Staff", "ERPNext se secure connection", null);
        TextView note = text("OpenAI API key Android app mein nahi rahegi. Key ko ERPNext > Tailoring AI Settings mein save karein.", 15, color("#35506B"));
        note.setBackgroundColor(color("#E8F1FF"));
        note.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(note, matchWrap());

        EditText site = input("ERPNext URL, e.g. https://erp.example.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
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
        TextView banner = text("Customer Body Measurement", 22, color("#102A43"));
        banner.setTypeface(Typeface.DEFAULT_BOLD);
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(dp(12), dp(22), dp(12), dp(22));
        banner.setBackgroundColor(color("#E8F1FF"));
        root.addView(banner, matchWrap());

        Button start = primaryButton("＋ New Customer Measurement");
        start.setOnClickListener(v -> showCustomerSearch());
        root.addView(start, buttonParams());

        Button recent = secondaryButton("Recent Measurement Sessions");
        recent.setOnClickListener(v -> loadRecentSessions());
        root.addView(recent, buttonParams());

        Button settings = secondaryButton("ERPNext Connection Settings");
        settings.setOnClickListener(v -> showConnection());
        root.addView(settings, buttonParams());

        root.addView(sectionTitle("Complete staff flow"));
        root.addView(flowRow("1", "Customer search/select"));
        root.addView(flowRow("2", "Actual height aur weight"));
        root.addView(flowRow("3", "Front, side aur back guided photos"));
        root.addView(flowRow("4", "ERPNext par private upload + AI analysis"));
        root.addView(flowRow("5", "Staff review/edit + final save"));
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
            if (rows.length() == 0) {
                results.addView(emptyState("Koi customer nahi mila"));
                return;
            }
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
                choose.setOnClickListener(v -> {
                    customerId = id;
                    customerName = name;
                    showSessionSetup();
                });
                card.addView(choose, smallButtonParams());
                results.addView(card, cardParams());
            }
        });
    }

    private void showSessionSetup() {
        LinearLayout root = screen("Measurement Setup", customerName + "  •  " + customerId, this::showCustomerSearch);
        TextView guide = text("Accuracy ke liye actual height zaroor enter karein. Customer fitted kapdon mein fixed footprint par khada ho.", 15, color("#35506B"));
        guide.setPadding(dp(12), dp(12), dp(12), dp(12));
        guide.setBackgroundColor(color("#FFF4E5"));
        root.addView(guide, matchWrap());
        EditText height = input("Required", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText weight = input("Optional", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        Spinner unit = new Spinner(this);
        unit.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Inch", "CM"}));
        root.addView(field("Actual Height", height));
        root.addView(field("Weight Kg (optional)", weight));
        root.addView(field("Measurement Unit", unit));
        CheckBox consent = new CheckBox(this);
        consent.setText(getString(R.string.photo_consent));
        consent.setTextSize(14);
        consent.setPadding(0, dp(12), 0, dp(8));
        root.addView(consent, matchWrap());
        Button create = primaryButton("Create Session & Take Photos");
        create.setOnClickListener(v -> {
            String h = height.getText().toString().trim();
            if (h.isEmpty()) { toast("Actual height required hai"); return; }
            if (!consent.isChecked()) { toast("Customer photo consent confirm karna required hai"); return; }
            sessionUnit = String.valueOf(unit.getSelectedItem());
            JSONObject payload = new JSONObject();
            try {
                payload.put("customer", customerId);
                payload.put("body_height", Double.parseDouble(h));
                payload.put("unit", sessionUnit);
                payload.put("consent_confirmed", 1);
                if (!weight.getText().toString().trim().isEmpty()) payload.put("body_weight", Double.parseDouble(weight.getText().toString().trim()));
            } catch (Exception error) { toast("Height/weight sahi number mein dalein"); return; }
            runTask("Measurement session create ho raha hai…", () -> api.callObject(API_PREFIX + "create_measurement_session", payload), result -> {
                sessionName = result.optString("name");
                photos.clear();
                showCaptureScreen();
            });
        });
        root.addView(create, buttonParams());
    }

    private void showCaptureScreen() {
        LinearLayout root = screen("Guided Photo Capture", customerName + "  •  " + sessionName, this::showSessionSetup);
        TextView instructions = text("Customer: fixed footprint par seedha khada ho • arms body se thode door • full 2×2 inch grid visible ho • photographer fixed footprint se phone seedha rakhe.", 15, color("#35506B"));
        instructions.setPadding(dp(12), dp(12), dp(12), dp(12));
        instructions.setBackgroundColor(color("#EAF8F1"));
        root.addView(instructions, matchWrap());
        root.addView(photoCard("front", "1. Front Photo", "Face forward, dono pair/haath visible"), cardParams());
        root.addView(photoCard("side", "2. Side Photo", "Exactly 90° side pose, head straight"), cardParams());
        root.addView(photoCard("back", "3. Back Photo", "Back straight, full grid visible"), cardParams());

        Button analyze = primaryButton("Upload Photos & Get AI Measurements");
        analyze.setEnabled(photos.size() == 3);
        analyze.setAlpha(photos.size() == 3 ? 1f : 0.45f);
        analyze.setOnClickListener(v -> uploadAndAnalyze());
        root.addView(analyze, buttonParams());
        root.addView(text("Photos ERPNext mein private files ke roop mein save hongi. OpenAI request ERPNext server karega.", 13, color("#5E6C84")), matchWrap());
    }

    private LinearLayout photoCard(String type, String heading, String hint) {
        LinearLayout card = card();
        card.addView(text(heading, 18, color("#172B4D")));
        card.addView(text(hint, 14, color("#5E6C84")));
        File file = photos.get(type);
        if (file != null && file.exists()) {
            ImageView preview = new ImageView(this);
            preview.setImageBitmap(ImageUtils.preview(file));
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)));
            card.addView(statusText("✓ Photo captured", "#16845B"));
        } else {
            card.addView(statusText("Photo pending", "#C56A13"));
        }
        Button capture = secondaryButton(file == null ? "Open Camera" : "Retake Photo");
        capture.setOnClickListener(v -> startCamera(type));
        card.addView(capture, smallButtonParams());
        return card;
    }

    private void startCamera(String type) {
        pendingPhotoType = type;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            return;
        }
        launchCamera(type);
    }

    private void launchCamera(String type) {
        File directory = new File(getCacheDir(), "captures");
        if (!directory.exists() && !directory.mkdirs()) { showError(new Exception("Photo cache create nahi hua")); return; }
        File file = new File(directory, type + "-" + System.currentTimeMillis() + ".jpg");
        photos.put(type, file);
        Uri uri = CaptureFileProvider.uriFor(file, getPackageName() + ".capture");
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
        intent.setClipData(ClipData.newRawUri("Tailoring photo", uri));
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        int requestCode = "front".equals(type) ? FRONT_REQUEST : "side".equals(type) ? SIDE_REQUEST : BACK_REQUEST;
        try { startActivityForResult(intent, requestCode); }
        catch (Exception error) { photos.remove(type); showError(new Exception("Camera app available nahi hai")); }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        String type = requestCode == FRONT_REQUEST ? "front" : requestCode == SIDE_REQUEST ? "side" : requestCode == BACK_REQUEST ? "back" : "";
        if (type.isEmpty()) return;
        File file = photos.get(type);
        if (resultCode != RESULT_OK || file == null || !file.exists() || file.length() == 0) {
            photos.remove(type);
            toast("Photo capture cancel ya fail hua");
        }
        showCaptureScreen();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            launchCamera(pendingPhotoType);
        } else if (requestCode == CAMERA_PERMISSION) {
            toast("Photo ke liye camera permission required hai");
        }
    }

    private void uploadAndAnalyze() {
        runTask("3 photos upload aur AI analysis ho raha hai…", () -> {
            for (String type : new String[]{"front", "side", "back"}) {
                File file = photos.get(type);
                if (file == null) throw new IllegalStateException(type + " photo missing");
                JSONObject upload = new JSONObject();
                upload.put("session_name", sessionName);
                upload.put("photo_type", type);
                upload.put("filename", sessionName + "-" + type + ".jpg");
                upload.put("image_base64", ImageUtils.toUploadBase64(file));
                api.callObject(API_PREFIX + "upload_measurement_photo", upload);
            }
            JSONObject analyze = new JSONObject();
            analyze.put("session_name", sessionName);
            return api.callObject(API_PREFIX + "analyze_measurements", analyze);
        }, this::showAnalysisResult);
    }

    private void showAnalysisResult(JSONObject result) {
        String quality = result.optString("capture_quality", "review");
        JSONArray issues = result.optJSONArray("quality_issues");
        JSONArray measurements = result.optJSONArray("measurements");
        LinearLayout root = screen("AI Measurement Review", customerName + "  •  " + sessionName, this::showCaptureScreen);
        String qualityColor = "pass".equals(quality) ? "#16845B" : "fail".equals(quality) ? "#B42318" : "#C56A13";
        TextView qualityView = statusText("Capture Quality: " + quality.toUpperCase(Locale.US), qualityColor);
        qualityView.setTextSize(18);
        root.addView(qualityView, matchWrap());
        if (issues != null && issues.length() > 0) {
            StringBuilder text = new StringBuilder("Check these issues:\n");
            for (int i = 0; i < issues.length(); i++) text.append("• ").append(issues.optString(i)).append("\n");
            TextView warning = text(text.toString().trim(), 14, color("#7A3E00"));
            warning.setPadding(dp(12), dp(12), dp(12), dp(12));
            warning.setBackgroundColor(color("#FFF4E5"));
            root.addView(warning, matchWrap());
        }
        if (measurements == null || measurements.length() == 0) {
            root.addView(emptyState("AI measurement nahi mila. Photos dobara lein ya ERPNext settings check karein."));
            Button retry = primaryButton("Retake Photos");
            retry.setOnClickListener(v -> showCaptureScreen());
            root.addView(retry, buttonParams());
            Button manual = secondaryButton("Enter Measurements Manually");
            manual.setOnClickListener(v -> loadManualMeasurementEntry());
            root.addView(manual, buttonParams());
            return;
        }

        measurementInputs.clear();
        measurementMeta.clear();
        root.addView(sectionTitle("Staff verification required"));
        root.addView(text("Har value check karein. Galat naap edit karke Save Reviewed Measurements dabayein.", 14, color("#5E6C84")), matchWrap());
        for (int i = 0; i < measurements.length(); i++) {
            JSONObject item = measurements.optJSONObject(i);
            if (item == null) continue;
            String code = item.optString("code");
            String name = item.optString("name", code.replace('_', ' '));
            double value = item.optDouble("value", 0);
            double confidence = item.optDouble("confidence", 0);
            LinearLayout card = card();
            card.addView(text(name, 17, color("#172B4D")));
            card.addView(text(code + "  •  AI confidence " + Math.round(confidence) + "%", 12, color("#5E6C84")));
            EditText input = input("Value", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            input.setText(formatNumber(value));
            card.addView(field("Final value (" + sessionUnit + ")", input));
            String remarks = item.optString("remarks", "");
            if (!remarks.isEmpty()) card.addView(text(remarks, 13, color("#5E6C84")));
            measurementInputs.put(code, input);
            measurementMeta.put(code, item);
            root.addView(card, cardParams());
        }
        Button save = primaryButton("Save Reviewed Measurements");
        save.setOnClickListener(v -> saveReviewed());
        root.addView(save, buttonParams());
    }

    private void loadManualMeasurementEntry() {
        runTask("Measurement fields load ho rahe hain…", () -> api.callArray(API_PREFIX + "get_measurement_definitions", new JSONObject()), definitions -> {
            JSONArray measurements = new JSONArray();
            for (int i = 0; i < definitions.length(); i++) {
                JSONObject definition = definitions.optJSONObject(i);
                if (definition == null) continue;
                JSONObject row = new JSONObject();
                try {
                    row.put("code", definition.optString("measurement_code"));
                    row.put("name", definition.optString("measurement_name"));
                    row.put("value", 0);
                    row.put("confidence", 0);
                    row.put("remarks", "Manual measurement");
                    measurements.put(row);
                } catch (Exception ignored) { }
            }
            JSONObject manualResult = new JSONObject();
            try {
                manualResult.put("capture_quality", "review");
                manualResult.put("quality_issues", new JSONArray().put("Manual measurement entry mode"));
                manualResult.put("measurements", measurements);
            } catch (Exception ignored) { }
            showAnalysisResult(manualResult);
        });
    }

    private void saveReviewed() {
        JSONArray rows = new JSONArray();
        try {
            for (Map.Entry<String, EditText> entry : measurementInputs.entrySet()) {
                String text = entry.getValue().getText().toString().trim();
                double value = Double.parseDouble(text);
                if (value <= 0) throw new NumberFormatException();
                JSONObject original = measurementMeta.get(entry.getKey());
                JSONObject row = new JSONObject();
                row.put("code", entry.getKey());
                row.put("value", value);
                row.put("unit", sessionUnit);
                row.put("confidence", original == null ? 0 : original.optDouble("confidence", 0));
                row.put("remarks", original == null ? "" : original.optString("remarks", ""));
                row.put("edited", original == null || Math.abs(value - original.optDouble("value", value)) > 0.001);
                rows.put(row);
            }
        } catch (Exception error) {
            toast("Sab measurement values valid aur zero se bade hone chahiye");
            return;
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("session_name", sessionName);
            payload.put("measurements", rows.toString());
            payload.put("status", "Reviewed");
        } catch (Exception ignored) { }
        runTask("Reviewed measurements ERPNext mein save ho rahe hain…", () -> api.callObject(API_PREFIX + "save_reviewed_measurements", payload), result -> showSaved(result));
    }

    private void showSaved(JSONObject result) {
        LinearLayout root = screen("Measurement Saved", customerName, this::showHome);
        TextView success = text("✓ ERPNext mein successfully save ho gaya", 22, color("#16845B"));
        success.setTypeface(Typeface.DEFAULT_BOLD);
        success.setGravity(Gravity.CENTER);
        success.setPadding(dp(12), dp(30), dp(12), dp(30));
        root.addView(success, matchWrap());
        root.addView(text("Session: " + result.optString("name", sessionName), 16, color("#172B4D")), matchWrap());
        root.addView(text("Status: " + result.optString("status", "Reviewed"), 16, color("#172B4D")), matchWrap());
        Button another = primaryButton("New Customer Measurement");
        another.setOnClickListener(v -> showCustomerSearch());
        root.addView(another, buttonParams());
        Button home = secondaryButton("Back to Home");
        home.setOnClickListener(v -> showHome());
        root.addView(home, buttonParams());
    }

    private void loadRecentSessions() {
        JSONObject payload = new JSONObject();
        try { payload.put("limit", 40); } catch (Exception ignored) { }
        runTask("Recent sessions load ho rahe hain…", () -> api.callArray(API_PREFIX + "list_measurement_sessions", payload), this::showRecentSessions);
    }

    private void showRecentSessions(JSONArray sessions) {
        LinearLayout root = screen("Recent Sessions", "Latest ERPNext measurement records", this::showHome);
        if (sessions.length() == 0) { root.addView(emptyState("Abhi koi measurement session nahi hai")); return; }
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject item = sessions.optJSONObject(i);
            if (item == null) continue;
            LinearLayout card = card();
            card.addView(text(item.optString("customer_name", item.optString("customer")), 17, color("#172B4D")));
            card.addView(text(item.optString("name") + "\n" + item.optString("measurement_date"), 13, color("#5E6C84")));
            card.addView(statusText(item.optString("status") + " • " + item.optString("analysis_status"), "Reviewed".equals(item.optString("status")) ? "#16845B" : "#C56A13"));
            root.addView(card, cardParams());
        }
    }

    private LinearLayout screen(String heading, String subtitle, Runnable back) {
        currentBack = back;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(color("#F4F7FA"));
        LinearLayout root = vertical();
        root.setPadding(dp(16), dp(12), dp(16), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        if (back != null) {
            Button backButton = secondaryButton("‹ Back");
            backButton.setOnClickListener(v -> back.run());
            header.addView(backButton, new LinearLayout.LayoutParams(dp(88), dp(44)));
        }
        LinearLayout headerText = vertical();
        TextView title = text(heading, 24, color("#102A43"));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        headerText.addView(title);
        if (subtitle != null && !subtitle.isEmpty()) headerText.addView(text(subtitle, 13, color("#5E6C84")));
        LinearLayout.LayoutParams headerTextParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        headerTextParams.setMargins(back == null ? 0 : dp(8), 0, 0, 0);
        header.addView(headerText, headerTextParams);
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        spacer(root, 14);
        setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.requestApplyInsets();
        return root;
    }

    private LinearLayout field(String label, View control) {
        LinearLayout box = vertical();
        box.setPadding(0, dp(8), 0, dp(5));
        TextView labelView = text(label, 13, color("#35506B"));
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(labelView);
        box.addView(control, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        return box;
    }

    private LinearLayout card() {
        LinearLayout card = vertical();
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackgroundColor(Color.WHITE);
        card.setElevation(dp(2));
        return card;
    }

    private LinearLayout flowRow(String number, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = text(number, 16, Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setBackgroundColor(color("#1769E0"));
        row.addView(badge, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView labelView = text(label, 15, color("#172B4D"));
        labelView.setPadding(dp(12), dp(10), 0, dp(10));
        row.addView(labelView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value, 18, color("#102A43"));
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(18), 0, dp(8));
        return view;
    }

    private TextView statusText(String value, String color) {
        TextView view = text(value, 14, color(color));
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(8), 0, dp(5));
        return view;
    }

    private TextView emptyState(String value) {
        TextView view = text(value, 16, color("#5E6C84"));
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(35), dp(12), dp(35));
        return view;
    }

    private EditText input(String hint, int inputType) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextSize(16);
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setBackgroundColor(Color.WHITE);
        return input;
    }

    private Button primaryButton(String label) { return styledButton(label, "#1769E0", "#FFFFFF"); }
    private Button secondaryButton(String label) { return styledButton(label, "#E8F1FF", "#174EA6"); }

    private Button styledButton(String label, String background, String foreground) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setTextColor(color(foreground));
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackgroundColor(color(background));
        return button;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.12f);
        return view;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams cardParams() { LinearLayout.LayoutParams p = matchWrap(); p.setMargins(0, dp(7), 0, dp(7)); return p; }
    private LinearLayout.LayoutParams buttonParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)); p.setMargins(0, dp(8), 0, dp(5)); return p; }
    private LinearLayout.LayoutParams smallButtonParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)); p.setMargins(0, dp(8), 0, 0); return p; }

    private void spacer(LinearLayout root, int height) { SpaceView space = new SpaceView(this); root.addView(space, new LinearLayout.LayoutParams(1, dp(height))); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int color(String value) { return Color.parseColor(value); }
    private String formatNumber(double value) { return Math.abs(value - Math.rint(value)) < 0.001 ? String.valueOf((long) value) : String.format(Locale.US, "%.2f", value); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }

    @Override
    public void onBackPressed() {
        if (currentBack != null) currentBack.run(); else super.onBackPressed();
    }

    private <T> void runTask(String message, Callable<T> work, ResultHandler<T> success) {
        runTask(message, work, success, this::showError);
    }

    private <T> void runTask(String message, Callable<T> work, ResultHandler<T> success, ErrorHandler failure) {
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage(message);
        progress.setCancelable(false);
        progress.show();
        executor.submit(() -> {
            try {
                T result = work.call();
                runOnUiThread(() -> { progress.dismiss(); success.accept(result); });
            } catch (Exception error) {
                runOnUiThread(() -> { progress.dismiss(); failure.accept(error); });
            }
        });
    }

    private void showError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
        new AlertDialog.Builder(this).setTitle("Action failed").setMessage(message).setPositiveButton("OK", null).show();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private interface ResultHandler<T> { void accept(T value); }
    private interface ErrorHandler { void accept(Exception error); }

    private static final class SpaceView extends View {
        SpaceView(Activity activity) { super(activity); }
    }
}
