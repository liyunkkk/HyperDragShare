package com.leaf.hyperdragshare.codex;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XC_MethodHook;

/**
 * Installs the accessibility protection into system_server after the other
 * system services finish starting. This is the only class in the module that
 * touches the system_server entry point; it must not reference module
 * application state.
 */
final class AccessibilityProtectionHooks {
    private static final String TAG = "DragShare/Protection";
    private static final String SYSTEM_LOG_FILE = "/data/local/tmp/HyperDragShare/system-server.log";

    private static final String SYSTEM_SERVER_CLASS = "com.android.server.SystemServer";
    private static final String TIMINGS_TRACE_AND_SLOG_CLASS =
            "com.android.server.utils.TimingsTraceAndSlog";
    private static final String BACKGROUND_THREAD_CLASS =
            "com.android.internal.os.BackgroundThread";

    private static volatile AccessibilityServiceEnforcer enforcer;
    private static final AtomicInteger startAttempts = new AtomicInteger();
    private static final int MAX_START_ATTEMPTS = 5;

    private AccessibilityProtectionHooks() {}

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

    /** Called from MainHook when packageName is "android". */
    static void install(ClassLoader classLoader) {
        if (classLoader == null) {
            Log.w(TAG, "no system_server class loader for accessibility protection");
            systemLog("install(classLoader=null) 被调用，classLoader 为空");
            return;
        }
        boolean installed = tryHookStartOtherServices(classLoader);
        if (!installed) {
            installed = tryHookSystemServerMain(classLoader);
        }
        if (!installed) {
            Log.w(TAG, "unable to install accessibility protection hook on any entry point");
            systemLog("全部 hook 入口均安装失败");
        }
    }

    private static boolean tryHookStartOtherServices(ClassLoader classLoader) {
        try {
            final Class<?> systemServerClass = XposedHelpers.findClass(
                    SYSTEM_SERVER_CLASS,
                    classLoader);
            final Class<?> timingsClass = XposedHelpers.findClass(
                    TIMINGS_TRACE_AND_SLOG_CLASS,
                    classLoader);
            XposedHelpers.findAndHookMethod(
                    systemServerClass,
                    "startOtherServices",
                    timingsClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            startEnforcer(param.thisObject, classLoader, "start_other_services");
                        }
                    });
            Log.i(TAG, "hooked SystemServer.startOtherServices");
            systemLog("已 hook SystemServer.startOtherServices");
            return true;
        } catch (Throwable failure) {
            Log.w(TAG, "unable to hook SystemServer.startOtherServices: "
                    + failure.getClass().getSimpleName());
            systemLog("hook startOtherServices 失败: " + failure.getClass().getSimpleName()
                    + ": " + failure.getMessage());
            return false;
        }
    }

    /**
     * Fallback entry point. SystemServer.main(String[]) exists on every API
     * level with a stable signature; it runs after startOtherServices has
     * finished, so enforcer startup there is safe. The method is static, so
     * thisObject is null and context resolution falls back to ActivityThread.
     */
    private static boolean tryHookSystemServerMain(ClassLoader classLoader) {
        try {
            final Class<?> systemServerClass = XposedHelpers.findClass(
                    SYSTEM_SERVER_CLASS,
                    classLoader);
            XposedHelpers.findAndHookMethod(
                    systemServerClass,
                    "main",
                    String[].class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            startEnforcer(null, classLoader, "main_fallback");
                        }
                    });
            Log.i(TAG, "hooked SystemServer.main as fallback");
            systemLog("已 hook SystemServer.main 作为兜底入口");
            return true;
        } catch (Throwable failure) {
            Log.w(TAG, "unable to hook SystemServer.main: "
                    + failure.getClass().getSimpleName());
            systemLog("hook SystemServer.main 失败: " + failure.getClass().getSimpleName()
                    + ": " + failure.getMessage());
            return false;
        }
    }

    private static synchronized void startEnforcer(
            Object systemServer,
            ClassLoader classLoader,
            String reason) {
        if (enforcer != null) {
            systemLog("enforcer 已存在，忽略重复启动请求 (" + reason + ")");
            return;
        }
        Context context = resolveSystemContext(systemServer);
        if (context == null) {
            Log.w(TAG, "SystemServer 已启动，但无法取得 system context");
            systemLog("无法取得 system context，启动失败 (" + reason + ")");
            scheduleStartRetry(classLoader, reason);
            return;
        }
        Handler handler = resolveSystemBackgroundHandler(classLoader);
        if (handler == null) {
            Log.w(TAG, "无法取得 Android BackgroundThread，跳过无障碍保护");
            systemLog("无法取得 BackgroundThread Handler，启动失败 (" + reason + ")");
            scheduleStartRetry(classLoader, reason);
            return;
        }
        AccessibilityServiceEnforcer enforcer = new AccessibilityServiceEnforcer(handler);
        AccessibilityProtectionHooks.enforcer = enforcer;
        enforcer.start(context);
        startAttempts.set(0);
        systemLog("enforcer 已创建并启动，原因=" + reason);
    }

    private static void scheduleStartRetry(ClassLoader classLoader, String reason) {
        if (startAttempts.getAndIncrement() >= MAX_START_ATTEMPTS) {
            systemLog("enforcer 启动重试次数已用尽");
            return;
        }
        try {
            final Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.postDelayed(
                    () -> startEnforcer(null, classLoader, reason + "_retry"),
                    2_000L);
            systemLog("已安排 enforcer 启动重试 (attempt=" + startAttempts.get() + ")");
        } catch (Throwable failure) {
            systemLog("无法安排 enforcer 启动重试: " + failure.getClass().getSimpleName());
        }
    }

    private static Context resolveSystemContext(Object systemServer) {
        Context fromSystemServer = contextFromOwner(systemServer);
        if (fromSystemServer != null) {
            return fromSystemServer;
        }
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentThreadMethod = activityThreadClass
                    .getDeclaredMethod("currentActivityThread");
            currentThreadMethod.setAccessible(true);
            Object currentThread = currentThreadMethod.invoke(null);
            if (currentThread == null) {
                return null;
            }
            Method getSystemContext = activityThreadClass
                    .getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            return (Context) getSystemContext.invoke(currentThread);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Context contextFromOwner(Object owner) {
        if (owner == null) {
            return null;
        }
        String[] fieldNames = new String[] {"mSystemContext", "mContext"};
        for (String name : fieldNames) {
            Field field = findField(owner.getClass(), name);
            if (field == null) {
                continue;
            }
            try {
                field.setAccessible(true);
                Context context = (Context) field.get(owner);
                if (context != null) {
                    return context;
                }
            } catch (Throwable ignored) {
                // Try the next candidate.
            }
        }
        String[] methodNames = new String[] {"getSystemContext", "getContext"};
        for (String name : methodNames) {
            Method method = findMethod(owner.getClass(), name);
            if (method == null) {
                continue;
            }
            try {
                method.setAccessible(true);
                Context context = (Context) method.invoke(owner);
                if (context != null) {
                    return context;
                }
            } catch (Throwable ignored) {
                // Try the next candidate.
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Handler resolveSystemBackgroundHandler(ClassLoader classLoader) {
        try {
            Class<?> backgroundThreadClass = XposedHelpers.findClass(
                    BACKGROUND_THREAD_CLASS,
                    classLoader);
            Method getHandler = backgroundThreadClass.getDeclaredMethod("getHandler");
            getHandler.setAccessible(true);
            Object handler = getHandler.invoke(null);
            return handler instanceof Handler ? (Handler) handler : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}