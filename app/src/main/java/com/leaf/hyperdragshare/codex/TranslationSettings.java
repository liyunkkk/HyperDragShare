package com.leaf.hyperdragshare.codex;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Translation preferences for the BigBang dictionary action. Kept separate
 * from {@link DragShareSettings} so the long constructor chain stays
 * untouched; it reads and writes the same private preferences file.
 */
public final class TranslationSettings {
    public static final int ENGINE_API = 0;
    public static final int ENGINE_ML_KIT = 1;
    public static final int ENGINE_AUTO = 2;
    public static final int DEFAULT_ENGINE = ENGINE_API;

    public static final int TARGET_AUTO = 0;
    public static final int TARGET_CHINESE = 1;
    public static final int TARGET_ENGLISH = 2;
    public static final int DEFAULT_TARGET = TARGET_AUTO;

    public static final String DEFAULT_API_ENDPOINT =
            "https://translate.googleapis.com/translate_a/single";

    private static final String PREFS_NAME = "drag_share_settings";
    private static final String KEY_ENGINE = "translation_engine";
    private static final String KEY_TARGET = "translation_target";
    private static final String KEY_API_ENDPOINT = "translation_api_endpoint";
    private static final String KEY_API_KEY = "translation_api_key";

    private static final String DEFAULT_API_KEY = "";

    public final int engine;
    public final int target;
    public final String apiEndpoint;
    public final String apiKey;

    public TranslationSettings(int engine, int target, String apiEndpoint, String apiKey) {
        this.engine = normalizeEngine(engine);
        this.target = normalizeTarget(target);
        this.apiEndpoint = apiEndpoint == null || apiEndpoint.trim().isEmpty()
                ? DEFAULT_API_ENDPOINT
                : apiEndpoint.trim();
        this.apiKey = apiKey == null ? DEFAULT_API_KEY : apiKey.trim();
    }

    public static TranslationSettings readLocal(Context context) {
        if (context == null) {
            return defaults();
        }
        SharedPreferences preferences = context.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE);
        return new TranslationSettings(
                preferences.getInt(KEY_ENGINE, DEFAULT_ENGINE),
                preferences.getInt(KEY_TARGET, DEFAULT_TARGET),
                preferences.getString(KEY_API_ENDPOINT, DEFAULT_API_ENDPOINT),
                preferences.getString(KEY_API_KEY, DEFAULT_API_KEY));
    }

    public void saveLocal(Context context) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_ENGINE, engine)
                .putInt(KEY_TARGET, target)
                .putString(KEY_API_ENDPOINT, apiEndpoint)
                .putString(KEY_API_KEY, apiKey)
                .apply();
    }

    public static TranslationSettings defaults() {
        return new TranslationSettings(DEFAULT_ENGINE, DEFAULT_TARGET, DEFAULT_API_ENDPOINT, "");
    }

    private static int normalizeEngine(int engine) {
        return engine == ENGINE_ML_KIT || engine == ENGINE_AUTO ? engine : ENGINE_API;
    }

    private static int normalizeTarget(int target) {
        return target == TARGET_CHINESE || target == TARGET_ENGLISH ? target : TARGET_AUTO;
    }
}
