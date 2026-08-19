package com.leaf.hyperdragshare.codex;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

public final class MainHook extends XposedModule {
    private static final String TAG = "DragShare";
    private static final String SYSTEM_LOG_FILE =
            "/data/local/tmp/HyperDragShare/system-server.log";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        systemLog("MainHook.onModuleLoaded: process=" + param.getProcessName()
                + " isSystemServer=" + param.isSystemServer()
                + " version=" + BuildConfig.VERSION_NAME
                + "(" + BuildConfig.VERSION_CODE + ")");
    }

    /** system_server 的现代入口，替代旧模型按 "android" 包名的 handleLoadPackage 过滤。 */
    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        systemLog("MainHook.onSystemServerStarting 命中 system_server: classLoader="
                + (param.getClassLoader() == null
                ? "null" : param.getClassLoader().getClass().getName())
                + " version=" + BuildConfig.VERSION_NAME
                + "(" + BuildConfig.VERSION_CODE + ")");
        log(Log.INFO, TAG, "installing accessibility protection in system server");
        AccessibilityProtectionHooks.install(this, param.getClassLoader());
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