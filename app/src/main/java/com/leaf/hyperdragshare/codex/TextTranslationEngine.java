package com.leaf.hyperdragshare.codex;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Translation engine for the BigBang dictionary action. The language decision
 * and API URL construction are pure functions so they stay unit-testable; the
 * network and ML Kit paths run on the caller's worker thread.
 */
public final class TextTranslationEngine {
    private static final String TAG = "DragShare/Translate";
    private static final String PARAM_SL = "sl";
    private static final String PARAM_TL = "tl";
    private static final String PARAM_QT = "dt";
    private static final String PARAM_Q = "q";
    private static final String DT_VALUE = "t";
    private static final String SL_AUTO = "auto";
    private static final int CONNECT_TIMEOUT_MILLIS = 8_000;
    private static final int READ_TIMEOUT_MILLIS = 12_000;

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

    /** Builds the request URL for the translate_a/single style endpoint. */
    public static String buildApiUrl(String endpoint, String targetLanguage, String text) {
        String normalized = endpoint == null || endpoint.trim().isEmpty()
                ? TranslationSettings.DEFAULT_API_ENDPOINT
                : endpoint.trim();
        try {
            return normalized
                    + (normalized.contains("?") ? "&" : "?")
                    + PARAM_SL + "=" + SL_AUTO
                    + "&" + PARAM_TL + "=" + targetLanguage
                    + "&" + PARAM_QT + "=" + DT_VALUE
                    + "&" + PARAM_Q + "=" + URLEncoder.encode(text, "UTF-8");
        } catch (IOException impossible) {
            throw new IllegalStateException("UTF-8 is always available", impossible);
        }
    }

    /**
     * Parses a translate_a/single response into the joined translation.
     * The payload is {@code [[["text","source",...],...], null, ...]}.
     */
    public static String parseApiResponse(String responseBody) throws JSONException {
        if (responseBody == null || responseBody.isEmpty()) {
            throw new JSONException("empty translation response");
        }
        JSONArray root = new JSONArray(responseBody);
        JSONArray sentences = root.optJSONArray(0);
        if (sentences == null) {
            throw new JSONException("missing sentence array");
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < sentences.length(); index++) {
            JSONArray sentence = sentences.optJSONArray(index);
            if (sentence == null || sentence.length() == 0) {
                continue;
            }
            String translated = sentence.optString(0);
            if (translated != null && !translated.isEmpty()) {
                builder.append(translated);
            }
        }
        String result = builder.toString().trim();
        if (result.isEmpty()) {
            throw new JSONException("empty translation result");
        }
        return result;
    }

    /**
     * Translates using the configured engine preference. This is a blocking
     * call and must run on a worker thread; it throws on any failure so the
     * caller can surface a message.
     */
    public static String translate(Context context, String text, TranslationSettings settings)
            throws Exception {
        String targetLanguage = resolveTargetLanguage(text, settings.target);
        if (settings.engine == TranslationSettings.ENGINE_ML_KIT) {
            return translateWithMlKit(context, text, targetLanguage);
        }
        if (settings.engine == TranslationSettings.ENGINE_AUTO) {
            try {
                return translateWithApi(text, targetLanguage, settings);
            } catch (Exception apiFailure) {
                DragShareLog.w(TAG, "API translation failed, falling back to on-device", apiFailure);
                return translateWithMlKit(context, text, targetLanguage);
            }
        }
        return translateWithApi(text, targetLanguage, settings);
    }

    private static String translateWithApi(String text, String targetLanguage,
                                           TranslationSettings settings) throws Exception {
        String url = buildApiUrl(settings.apiEndpoint, targetLanguage, text);
        String body = httpGet(url);
        try {
            return parseApiResponse(body);
        } catch (JSONException malformed) {
            throw new IOException("translation response could not be parsed", malformed);
        }
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("translation API returned HTTP " + status);
            }
            InputStream input = connection.getInputStream();
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

    private static String translateWithMlKit(Context context, String text, String targetLanguage)
            throws Exception {
        MlKitTranslateBridge bridge = new MlKitTranslateBridge();
        return bridge.translate(context, text, targetLanguage);
    }
}
