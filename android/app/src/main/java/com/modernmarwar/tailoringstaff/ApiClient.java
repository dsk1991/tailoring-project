package com.modernmarwar.tailoringstaff;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class ApiClient {
    private final String siteUrl;
    private final String authorization;

    ApiClient(String siteUrl, String apiKey, String apiSecret) {
        String clean = siteUrl == null ? "" : siteUrl.trim();
        while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        this.siteUrl = clean;
        this.authorization = "token " + apiKey.trim() + ":" + apiSecret.trim();
    }

    JSONObject callObject(String method, JSONObject payload) throws Exception {
        Object message = call(method, payload);
        if (message instanceof JSONObject) return (JSONObject) message;
        throw new ApiException("ERPNext returned an unexpected response");
    }

    JSONArray callArray(String method, JSONObject payload) throws Exception {
        Object message = call(method, payload);
        if (message instanceof JSONArray) return (JSONArray) message;
        throw new ApiException("ERPNext returned an unexpected list response");
    }

    private Object call(String method, JSONObject payload) throws Exception {
        if (!(siteUrl.startsWith("https://") || siteUrl.startsWith("http://"))) {
            throw new ApiException("ERPNext URL must start with https:// or http://");
        }
        URL baseUrl = new URL(siteUrl);
        String host = baseUrl.getHost();
        boolean localDevelopment = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "10.0.2.2".equals(host);
        if (!"https".equalsIgnoreCase(baseUrl.getProtocol()) && !localDevelopment) {
            throw new ApiException("Customer photos ke liye production ERPNext URL HTTPS hona chahiye");
        }
        URL url = new URL(siteUrl + "/api/method/" + method);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(150000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", authorization);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] body = (payload == null ? new JSONObject() : payload).toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = connection.getOutputStream()) { output.write(body); }

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String text = readText(stream);
        JSONObject root;
        try { root = new JSONObject(text); }
        catch (JSONException error) { throw new ApiException("ERPNext HTTP " + code + ": " + limit(text)); }
        if (code < 200 || code >= 300 || root.has("exc")) {
            throw new ApiException(extractError(root, code));
        }
        if (!root.has("message")) throw new ApiException("ERPNext response has no message field");
        return root.get("message");
    }

    private static String readText(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder value = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) value.append(line);
        }
        return value.toString();
    }

    private static String extractError(JSONObject root, int code) {
        String message = root.optString("exception", "");
        String serverMessages = root.optString("_server_messages", "");
        if (!serverMessages.isEmpty()) {
            try {
                JSONArray outer = new JSONArray(serverMessages);
                if (outer.length() > 0) {
                    JSONObject item = new JSONObject(outer.getString(0));
                    message = item.optString("message", message);
                }
            } catch (Exception ignored) { }
        }
        if (message.isEmpty()) message = root.optString("exc_type", "ERPNext request failed");
        return "HTTP " + code + ": " + limit(message.replace("<br>", "\n"));
    }

    private static String limit(String value) {
        if (value == null) return "Unknown error";
        return value.length() > 700 ? value.substring(0, 700) : value;
    }

    static final class ApiException extends Exception {
        ApiException(String message) { super(message); }
    }
}
