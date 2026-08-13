package com.leaf.hyperdragshare.codex;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Translation engine for the BigBang dictionary action, backed by an
 * OpenAI-compatible chat-completions endpoint. The language decision and
 * request/response construction are pure functions so they stay
 * unit-testable; the network call runs on the caller's worker thread.
 */
public final class TextTranslationEngine {
    private static final String TAG = "DragShare/Translate";
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final int CONNECT_TIMEOUT_MILLIS = 8_000;
    private static final int READ_TIMEOUT_MILLIS = 30_000;

    private TextTranslationEngine() {}

    /**
     * Resolves the target language code for the given source text and the
     * user's target-language preference. "Auto" reverses the detected script:
     * CJK text translates to English, everything else to Chinese.
     */
    public static String resolveTargetLanguage(String text, int targetSetting) {
        if (targetSetting == TranslationSettings.TARGET_CHINESE) {
            return "zh";
        }
        if (targetSetting == TranslationSettings.TARGET_ENGLISH) {
            return "en";
        }
        return containsCjk(text) ? "en" : "zh";
    }

    /** Detects whether the text contains any CJK Unified Ideographs. */
    public static boolean containsCjk(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    /**
     * Builds the chat-completions URL for the given base URL, e.g.
     * {@code https://api.openai.com/v1} becomes
     * {@code https://api.openai.com/v1/chat/completions}.
     */
    public static String buildChatUrl(String baseUrl) {
        String normalized = baseUrl == null || baseUrl.trim().isEmpty()
                ? TranslationSettings.DEFAULT_BASE_URL
                : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/chat/completions")) {
            return normalized;
        }
        return normalized + CHAT_COMPLETIONS_PATH;
    }

    /** Names the target language in a human-readable way for the prompt. */
    public static String targetLanguageName(String targetLanguage) {
        return "zh".equals(targetLanguage) ? "Chinese" : "English";
    }

    /**
     * Builds the JSON request body for a chat-completions translation call.
     */
    public static String buildRequestJson(String model, String targetLanguage, String text)
            throws JSONException {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0.2);
        JSONArray messages = new JSONArray();
        JSONObject system = new JSONObject();
        system.put("role", "system");
        system.put("content", "You are a professional translator. Translate the user's text into "
                + targetLanguageName(targetLanguage)
                + ". Return only the translated text, without explanations or quotation marks.");
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", text);
        messages.put(system);
        messages.put(user);
        body.put("messages", messages);
        return body.toString();
    }

    /**
     * Extracts the assistant message from a chat-completions response:
     * {@code {"choices":[{"message":{"role":"assistant","content":"..."}}]}}.
     */
    public static String parseChatResponse(String responseBody) throws JSONException {
        if (responseBody == null || responseBody.isEmpty()) {
            throw new JSONException("empty translation response");
        }
        JSONObject root = new JSONObject(responseBody);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new JSONException("missing choices array");
        }
        JSONObject choice = choices.optJSONObject(0);
        JSONObject message = choice == null ? null : choice.optJSONObject("message");
        String content = message == null ? null : message.optString("content", "");
        if (content == null || content.trim().isEmpty()) {
            throw new JSONException("empty translation result");
        }
        return content.trim();
    }

    /**
     * Translates using the configured OpenAI-compatible endpoint. This is a
     * blocking call and must run on a worker thread; it throws on any failure
     * so the caller can surface a message.
     */
    public static String translate(Context context, String text, TranslationSettings settings)
            throws Exception {
        String targetLanguage = resolveTargetLanguage(text, settings.target);
        String url = buildChatUrl(settings.baseUrl);
        String requestBody = buildRequestJson(settings.model, targetLanguage, text);
        String responseBody = httpPost(url, settings.apiKey, requestBody);
        try {
            return parseChatResponse(responseBody);
        } catch (JSONException malformed) {
            throw new IOException("translation response could not be parsed", malformed);
        }
    }

    private static String httpPost(String url, String apiKey, String requestBody)
            throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            if (apiKey != null && !apiKey.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            byte[] payload = requestBody.getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            if (status < 200 || status >= 300) {
                String errorBody = readAll(input);
                throw new IOException("translation API returned HTTP " + status + ": " + errorBody);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                StringBuilder builder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                }
                return builder.toString();
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readAll(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return builder.toString();
        }
    }
}
