package com.leaf.hyperdragshare.codex;
import android.app.Application;
/** Starts the optional tokenizer warm-up for every module-app process. */
public final class DragShareApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DragShareLog.configure(DragShareSettings.readLocal(this));
        DragShareDiagnostics.captureRuntimeOnce(this, "module application created", null);
        AccessibilityKeepAlive.sync(this);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Process-wide memory relief: when this process (any component, not just the
        // accessibility service) goes to the background or the system is low on memory,
        // drop the cached ML Kit recognizer and its native model. Re-created lazily on
        // the next OCR request, so no functionality is lost.
        if (level >= TRIM_MEMORY_BACKGROUND) {
            ImageOcrEngine.releaseRecognizer();
        }
    }
}
