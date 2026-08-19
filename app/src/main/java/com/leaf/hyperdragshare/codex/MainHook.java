package com.leaf.hyperdragshare.codex;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainHook implements IXposedHookLoadPackage {
    private static final String SYSTEM_LOG_FILE =
            "/data/local/tmp/HyperDragShare/system-server.log";
    /** LSPosed matches the system_server process by the "android" package name. */
    static final String SYSTEM_SERVER_PACKAGE = "android";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (SYSTEM_SERVER_PACKAGE.equals(lpparam.packageName)) {
            systemLog("MainHook.handleLoadPackage 命中 system_server: package="
                    + lpparam.packageName
                    + " classLoader=" + (lpparam.classLoader == null
                    ? "null" : lpparam.classLoader.getClass().getName())
                    + " version=" + BuildConfig.VERSION_NAME
                    + "(" + BuildConfig.VERSION_CODE + ")");
            AccessibilityProtectionHooks.install(lpparam.classLoader);
        }
    }

    private static void systemLog(String message) {
        try {
            File directory = new File("/data/local/tmp/HyperDragShare");
            if (!directory.exists() && !directory.mkdirs()) {
                return;
            }
            String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                    + " [system_server] " + message + "\n";
            try (FileOutputStream output = new FileOutputStream(SYSTEM_LOG_FILE, true)) {
                output.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // File logging is best effort diagnostic aid only.
        }
    }
}