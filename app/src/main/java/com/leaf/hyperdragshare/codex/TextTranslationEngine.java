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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Translation engine for the BigBang dictionary action, backed by either an
 * OpenAI-compatible chat-completions endpoint or the Anthropic Messages API.
 * The URL, request and response construction are pure functions so they stay
 * unit-testable; network calls run on the caller's worker thread.
 */
public final class TextTranslationEngine {
    private static final String TAG = "DragShare/Translate";
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String MESSAGES_PATH = "/messages";
    private static final String MODELS_PATH = "/models";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String AUTH_HEADER = "Authorization";
    private static final String AUTH_PREFIX = "Bearer ";
    private static final String X_API_KEY = "x-api-key";
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
     * Builds the chat URL for the given base URL and API type. OpenAI becomes
     * {@code {baseUrl}/chat/completions}; Claude becomes
     * {@code {baseUrl}/messages}.
     */
    public static String buildChatUrl(String baseUrl, int apiType) {
        String normalized = trimTrailingSlashes(baseUrl);
        if (apiType == TranslationSettings.API_TYPE_CLAUDE) {
            return normalized.endsWith(MESSAGES_PATH)
                    ? normalized
                    : normalized + MESSAGES_PATH;
        }
        return normalized.endsWith(CHAT_COMPLETIONS_PATH)
                ? normalized
                : normalized + CHAT_COMPLETIONS_PATH;
    }

    /** Builds the model-list URL: {@code {baseUrl}/models}. */
    public static String buildModelsUrl(String baseUrl) {
        String normalized = trimTrailingSlashes(baseUrl);
        return normalized.endsWith(MODELS_PATH) ? normalized : normalized + MODELS_PATH;
    }

    /** Names the target language in a human-readable way for the prompt. */
    public static String targetLanguageName(String targetLanguage) {
        return "zh".equals(targetLanguage) ? "Chinese" : "English";
    }

    /**
     * Builds the JSON request body for a translation call using the given
     * system prompt.
     */
    public static String buildRequestJson(int apiType, String model, String systemPrompt,
                                          String text) throws JSONException {
        if (apiType == TranslationSettings.API_TYPE_CLAUDE) {
            return buildClaudeRequestJson(model, systemPrompt, text);
        }
        return buildOpenAiRequestJson(model, systemPrompt, text);
    }

    private static String buildOpenAiRequestJson(String model, String systemPrompt, String text)
            throws JSONException {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0.2);
        JSONArray messages = new JSONArray();
        JSONObject system = new JSONObject();
        system.put("role", "system");
        system.put("content", systemPrompt);
        messages.put(system);
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", text);
        messages.put(user);
        body.put("messages", messages);
        return body.toString();
    }

    private static String buildClaudeRequestJson(String model, String systemPrompt, String text)
            throws JSONException {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", 1024);
        body.put("temperature", 0.2);
        body.put("system", systemPrompt);
        JSONArray messages = new JSONArray();
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", text);
        messages.put(user);
        body.put("messages", messages);
        return body.toString();
    }

    /**
     * The built-in translator instruction used when the user has not written a
     * custom role prompt.
     */
    public static String defaultSystemPrompt(String targetLanguage) {
        return "You are a professional translator. Translate the user's text into "
                + targetLanguageName(targetLanguage)
                + ". Return only the translated text, without explanations or quotation marks.";
    }

    /**
     * Extracts the translated text from a chat response.
     * OpenAI: {@code {"choices":[{"message":{"content":"..."}}]}}.
     * Claude: {@code {"content":[{"type":"text","text":"..."}]}}.
     */
    public static String parseChatResponse(int apiType, String responseBody) throws JSONException {
        if (responseBody == null || responseBody.isEmpty()) {
            throw new JSONException("empty translation response");
        }
        if (apiType == TranslationSettings.API_TYPE_CLAUDE) {
            return parseClaudeResponse(responseBody);
        }
        return parseOpenAiResponse(responseBody);
    }

    private static String parseOpenAiResponse(String responseBody) throws JSONException {
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

    private static String parseClaudeResponse(String responseBody) throws JSONException {
        JSONObject root = new JSONObject(responseBody);
        JSONArray content = root.optJSONArray("content");
        if (content == null || content.length() == 0) {
            throw new JSONException("missing content array");
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < content.length(); index++) {
            JSONObject part = content.optJSONObject(index);
            if (part == null) {
                continue;
            }
            String text = part.optString("text", "");
            if (!text.isEmpty()) {
                builder.append(text);
            }
        }
        String result = builder.toString().trim();
        if (result.isEmpty()) {
            throw new JSONException("empty translation result");
        }
        return result;
    }

    /** Parses a model-list response into a sorted list of model ids. */
    public static List<String> parseModelsResponse(String responseBody) throws JSONException {
        if (responseBody == null || responseBody.isEmpty()) {
            throw new JSONException("empty models response");
        }
        JSONObject root = new JSONObject(responseBody);
        JSONArray data = root.optJSONArray("data");
        if (data == null || data.length() == 0) {
            throw new JSONException("missing data array");
        }
        List<String> models = new ArrayList<>();
        for (int index = 0; index < data.length(); index++) {
            JSONObject item = data.optJSONObject(index);
            if (item == null) {
                continue;
            }
            String id = item.optString("id", "");
            if (!id.isEmpty()) {
                models.add(id);
            }
        }
        if (models.isEmpty()) {
            throw new JSONException("no model ids in response");
        }
        Collections.sort(models);
        return models;
    }

    /**
     * Translates using the configured endpoint. Blocking; must run on a worker
     * thread. Throws on any failure so the caller can surface a message.
     */
    public static String translate(Context context, String text, TranslationSettings settings)
            throws Exception {
        String systemPrompt = settings.rolePrompt.isEmpty()
                ? defaultSystemPrompt(resolveTargetLanguage(text, settings.target))
                : settings.rolePrompt;
        String url = buildChatUrl(settings.baseUrl, settings.apiType);
        String requestBody = buildRequestJson(
                settings.apiType, settings.effectiveModel(), systemPrompt, text);
        String responseBody = httpPost(url, settings.apiType, settings.apiKey, requestBody);
        try {
            return parseChatResponse(settings.apiType, responseBody);
        } catch (JSONException malformed) {
            throw new IOException("translation response could not be parsed", malformed);
        }
    }

    /**
     * Fetches the model ids available from the configured endpoint. Blocking;
     * must run on a worker thread.
     */
    public static List<String> fetchModels(TranslationSettings settings) throws Exception {
        String url = buildModelsUrl(settings.baseUrl);
        String responseBody = httpGet(url, settings.apiType, settings.apiKey);
        try {
            return parseModelsResponse(responseBody);
        } catch (JSONException malformed) {
            throw new IOException("models response could not be parsed", malformed);
        }
    }

    /**
     * Verifies that the endpoint answers with the configured key. Returns the
     * number of available models on success; throws on any failure.
     */
    public static int testConnection(TranslationSettings settings) throws Exception {
        return fetchModels(settings).size();
    }

    private static String httpPost(String url, int apiType, String apiKey, String requestBody)
            throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(url, apiType, apiKey);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            byte[] payload = requestBody.getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            return readResponse(connection);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String httpGet(String url, int apiType, String apiKey) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(url, apiType, apiKey);
            connection.setRequestMethod("GET");
            return readResponse(connection);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static HttpURLConnection openConnection(String url, int apiType, String apiKey)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) {
            if (apiType == TranslationSettings.API_TYPE_CLAUDE) {
                connection.setRequestProperty(X_API_KEY, apiKey);
                connection.setRequestProperty("anthropic-version", ANTHROPIC_VERSION);
            } else {
                connection.setRequestProperty(AUTH_HEADER, AUTH_PREFIX + apiKey);
            }
        }
        return connection;
    }

    private static String readResponse(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        InputStream input = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        if (status < 200 || status >= 300) {
            String errorBody = readAll(input);
            throw new IOException("translation API returned HTTP " + status + ": " + errorBody);
        }
        return readAll(input);
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

    private static String trimTrailingSlashes(String value) {
        String normalized = value == null || value.trim().isEmpty()
                ? TranslationSettings.DEFAULT_OPENAI_BASE_URL
                : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
