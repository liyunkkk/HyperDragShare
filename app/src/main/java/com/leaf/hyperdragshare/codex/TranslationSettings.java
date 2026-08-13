package com.leaf.hyperdragshare.codex;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Translation preferences for the BigBang dictionary action. The dictionary
 * button calls an LLM chat-completions endpoint (OpenAI-compatible or
 * Anthropic Messages API); these settings hold the provider type, base URL,
 * API key, model name and the AI translator role: a user-editable name and
 * system prompt that shape the translation for any domain or tone. Kept
 * separate from {@link DragShareSettings} so the long constructor chain stays
 * untouched; it reads and writes the same private preferences file.
 */
public final class TranslationSettings {
    public static final int API_TYPE_OPENAI = 0;
    public static final int API_TYPE_CLAUDE = 1;
    public static final int DEFAULT_API_TYPE = API_TYPE_OPENAI;

    public static final int TARGET_AUTO = 0;
    public static final int TARGET_CHINESE = 1;
    public static final int TARGET_ENGLISH = 2;
    public static final int DEFAULT_TARGET = TARGET_AUTO;

    public static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_CLAUDE_BASE_URL = "https://api.anthropic.com/v1";
    public static final String DEFAULT_OPENAI_MODEL = "gpt-4o-mini";
    public static final String DEFAULT_CLAUDE_MODEL = "claude-3-5-haiku-latest";

    public static final String DEFAULT_ROLE_NAME = "专业翻译官";

    private static final String PREFS_NAME = "drag_share_settings";
    private static final String KEY_API_TYPE = "translation_api_type";
    private static final String KEY_TARGET = "translation_target";
    private static final String KEY_BASE_URL = "translation_api_endpoint";
    private static final String KEY_API_KEY = "translation_api_key";
    private static final String KEY_MODEL = "translation_model";
    private static final String KEY_ROLE_NAME = "translation_role_name";
    private static final String KEY_ROLE_PROMPT = "translation_role_prompt";

    private static final String DEFAULT_API_KEY = "";

    public final int apiType;
    public final int target;
    public final String baseUrl;
    public final String apiKey;
    public final String model;
    public final String roleName;
    public final String rolePrompt;

    public TranslationSettings(int apiType, int target, String baseUrl, String apiKey, String model,
                               String roleName, String rolePrompt) {
        this.apiType = normalizeApiType(apiType);
        this.target = normalizeTarget(target);
        String defaultBaseUrl = this.apiType == API_TYPE_CLAUDE
                ? DEFAULT_CLAUDE_BASE_URL
                : DEFAULT_OPENAI_BASE_URL;
        this.baseUrl = baseUrl == null || baseUrl.trim().isEmpty()
                ? defaultBaseUrl
                : trimTrailingSlash(baseUrl.trim());
        this.apiKey = apiKey == null ? DEFAULT_API_KEY : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.roleName = roleName == null || roleName.trim().isEmpty()
                ? DEFAULT_ROLE_NAME
                : roleName.trim();
        this.rolePrompt = rolePrompt == null ? "" : rolePrompt.trim();
    }

    /** Returns the model actually used for requests, falling back to the type default. */
    public String effectiveModel() {
        return model.isEmpty()
                ? (apiType == API_TYPE_CLAUDE ? DEFAULT_CLAUDE_MODEL : DEFAULT_OPENAI_MODEL)
                : model;
    }

    public static TranslationSettings readLocal(Context context) {
        if (context == null) {
            return defaults();
        }
        SharedPreferences preferences = context.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE);
        return new TranslationSettings(
                preferences.getInt(KEY_API_TYPE, DEFAULT_API_TYPE),
                preferences.getInt(KEY_TARGET, DEFAULT_TARGET),
                preferences.getString(KEY_BASE_URL, ""),
                preferences.getString(KEY_API_KEY, DEFAULT_API_KEY),
                preferences.getString(KEY_MODEL, ""),
                preferences.getString(KEY_ROLE_NAME, ""),
                preferences.getString(KEY_ROLE_PROMPT, ""));
    }

    public void saveLocal(Context context) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_API_TYPE, apiType)
                .putInt(KEY_TARGET, target)
                .putString(KEY_BASE_URL, baseUrl)
                .putString(KEY_API_KEY, apiKey)
                .putString(KEY_MODEL, model)
                .putString(KEY_ROLE_NAME, roleName)
                .putString(KEY_ROLE_PROMPT, rolePrompt)
                .apply();
    }

    public static TranslationSettings defaults() {
        return new TranslationSettings(
                DEFAULT_API_TYPE, DEFAULT_TARGET, "", "", "", "", "");
    }

    private static int normalizeApiType(int apiType) {
        return apiType == API_TYPE_CLAUDE ? apiType : API_TYPE_OPENAI;
    }

    private static int normalizeTarget(int target) {
        return target == TARGET_CHINESE || target == TARGET_ENGLISH ? target : TARGET_AUTO;
    }

    private static String trimTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
