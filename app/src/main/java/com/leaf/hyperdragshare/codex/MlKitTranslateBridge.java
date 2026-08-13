package com.leaf.hyperdragshare.codex;

import android.content.Context;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.concurrent.TimeUnit;

/**
 * On-device translation backed by ML Kit's unbundled translation models.
 * The language model is downloaded on first use and cached by Google Play
 * services, so this path needs network only for that initial download.
 * All methods are blocking and must run on a worker thread.
 */
final class MlKitTranslateBridge {
    private static final String TAG = "DragShare/TranslateMlKit";
    private static final long MODEL_TIMEOUT_SECONDS = 60;
    private static final long TRANSLATE_TIMEOUT_SECONDS = 20;

    /** Resolves the ML Kit language constant for a "zh"/"en" style code. */
    static String mlKitLanguageCode(String languageCode) {
        if ("en".equals(languageCode)) {
            return TranslateLanguage.ENGLISH;
        }
        if ("zh".equals(languageCode) || "zh-CN".equals(languageCode)) {
            return TranslateLanguage.CHINESE;
        }
        return TranslateLanguage.CHINESE;
    }

    /**
     * Translates {@code text} into {@code targetLanguage}. The source language
     * is inferred by reversing the target: a CJK source translates to English
     * and a non-CJK source translates to Chinese, matching the auto-detection
     * behaviour of {@link TextTranslationEngine#resolveTargetLanguage}.
     */
    String translate(Context context, String text, String targetLanguage) throws Exception {
        String sourceLanguage = "zh".equals(targetLanguage) ? "en" : "zh";
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(mlKitLanguageCode(sourceLanguage))
                .setTargetLanguage(mlKitLanguageCode(targetLanguage))
                .build();
        Translator translator = Translation.getClient(options);
        try {
            Tasks.await(
                    translator.downloadModelIfNeeded(),
                    MODEL_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
            String translated = Tasks.await(
                    translator.translate(text),
                    TRANSLATE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
            if (translated == null || translated.trim().isEmpty()) {
                throw new IllegalStateException("ML Kit translation returned an empty result");
            }
            return translated.trim();
        } finally {
            translator.close();
        }
    }
}
