package com.leaf.hyperdragshare.codex;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Offline OCR for captured image payloads backed by Google ML Kit's bundled
 * text-recognition model. Recognition runs on a dedicated daemon executor and
 * never blocks the drag gesture or the main thread.
 */
final class ImageOcrEngine {
    interface Callback {
        void onResult(String text);

        void onFailure(Throwable error);
    }

    static final int MAX_DIMENSION_PX = 1280;
    static final long OCR_TIMEOUT_SECONDS = 15;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "drag-share-ocr");
        thread.setDaemon(true);
        return thread;
    });
    private static final Object RECOGNIZER_LOCK = new Object();
    private static TextRecognizer recognizer;

    private ImageOcrEngine() {}

    /** Pre-initializes the bundled model on a background thread to avoid first-gesture lag. */
    static void warmUp(Context context) {
        if (context == null) {
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                textRecognizer(context);
            } catch (Throwable ignored) {
                // Warm-up is best effort; recognition falls back to lazy init.
            }
        });
    }

    static void recognize(Context context, Bitmap bitmap, Callback callback) {
        recognize(context, bitmap, EXECUTOR, callback);
    }

    static void recognize(
            Context context,
            Bitmap bitmap,
            Executor executor,
            Callback callback) {
        if (context == null || bitmap == null || bitmap.isRecycled() || callback == null) {
            if (callback != null) {
                callback.onFailure(new IllegalArgumentException("invalid OCR input"));
            }
            return;
        }
        executor.execute(() -> {
            try {
                Text result = Tasks.await(
                        textRecognizer(context).process(
                                InputImage.fromBitmap(scaledForRecognition(bitmap), 0)),
                        OCR_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS);
                String text = result == null ? "" : result.getText();
                if (text == null) {
                    text = "";
                }
                callback.onResult(text.trim());
            } catch (Throwable error) {
                callback.onFailure(error);
            }
        });
    }

    /** Returns a copy scaled to {@link #MAX_DIMENSION_PX} at most, preserving aspect ratio. */
    static Bitmap scaledForRecognition(Bitmap source) {
        if (source == null || source.isRecycled()) {
            return source;
        }
        Rect bounds = computeScaledBounds(
                source.getWidth(),
                source.getHeight(),
                MAX_DIMENSION_PX);
        if (bounds.left == 0 && bounds.top == 0 && bounds.width() == 0) {
            return source;
        }
        return Bitmap.createScaledBitmap(source, bounds.width(), bounds.height(), true);
    }

    /**
     * Pure scaling math used by {@link #scaledForRecognition}. Returns an empty
     * rect (left=top=0, width=0, height=0) when no scaling is required.
     */
    static Rect computeScaledBounds(int width, int height, int maxDimension) {
        if (width <= 0 || height <= 0 || maxDimension <= 0) {
            return new Rect();
        }
        if (width <= maxDimension && height <= maxDimension) {
            return new Rect();
        }
        float scale = Math.min((float) maxDimension / width, (float) maxDimension / height);
        int scaledWidth = Math.max(1, Math.round(width * scale));
        int scaledHeight = Math.max(1, Math.round(height * scale));
        return new Rect(0, 0, scaledWidth, scaledHeight);
    }

    private static TextRecognizer textRecognizer(Context context) {
        synchronized (RECOGNIZER_LOCK) {
            if (recognizer == null) {
                recognizer = TextRecognition.getClient(
                        new ChineseTextRecognizerOptions.Builder().build());
            }
            return recognizer;
        }
    }
}
