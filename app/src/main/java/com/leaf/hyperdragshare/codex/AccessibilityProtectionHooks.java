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

import de.robv.android.xposed.XposedBridge;
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
    private static final String BACKGROUND_THREAD_CLASS =
            "com.android.internal.os.BackgroundThread";

    private static volatile AccessibilityServiceEnforcer enforcer;
    private static final AtomicInteger startAttempts = new AtomicInteger();
    private static final int MAX_START_ATTEMPTS = 5;
    // run() starts SystemServer.createSystemContext() early; 3s is enough for the
    // ActivityThread system context to exist by the time the delayed task runs.
    private static final long RUN_FALLBACK_DELAY_MS = 3_000L;

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
        systemLog("install() 已进入，classLoader=" + classLoader.getClass().getName());
        boolean installed = tryHookStartOtherServices(classLoader);
        if (!installed) {
            installed = tryHookSystemServerRun(classLoader);
        }
        if (!installed) {
            Log.w(TAG, "unable to install accessibility protection hook on any entry point");
            systemLog("全部 hook 入口均安装失败");
        }
    }

    private static boolean tryHookStartOtherServices(ClassLoader classLoader) {
        final Class<?> systemServerClass;
        try {
            systemServerClass = XposedHelpers.findClass(SYSTEM_SERVER_CLASS, classLoader);
        } catch (Throwable failure) {
            Log.w(TAG, "unable to load SystemServer class: "
                    + failure.getClass().getSimpleName());
            systemLog("hook startOtherServices 失败: 无法加载 SystemServer 类: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            return false;
        }
        int hooked = 0;
        for (Method method : systemServerClass.getDeclaredMethods()) {
            if (!"startOtherServices".equals(method.getName())) {
                continue;
            }
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        startEnforcer(param.thisObject, classLoader, "start_other_services");
                    }
                });
                hooked++;
            } catch (Throwable failure) {
                systemLog("hook startOtherServices 变体失败: "
                        + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
        }
        if (hooked == 0) {
            Log.w(TAG, "no startOtherServices overload could be hooked");
            systemLog("hook startOtherServices 失败: 未找到可 hook 的 startOtherServices 重载");
            return false;
        }
        Log.i(TAG, "hooked " + hooked + " SystemServer.startOtherServices overload(s)");
        systemLog("已 hook SystemServer.startOtherServices，命中重载数=" + hooked);
        return true;
    }

    /**
     * Fallback entry point. SystemServer.run() exists on every API level as a
     * stable parameterless method; it never returns (it enters the system main
     * Looper), so an afterHook would never fire. Instead the beforeHook schedules
     * a delayed startup attempt: by then run() has executed createSystemContext(),
     * so the system context is resolvable via ActivityThread even though no
     * thisObject is available for the static-ish entry.
     */
    private static boolean tryHookSystemServerRun(ClassLoader classLoader) {
        try {
            final Class<?> systemServerClass = XposedHelpers.findClass(
                    SYSTEM_SERVER_CLASS,
                    classLoader);
            for (Method method : systemServerClass.getDeclaredMethods()) {
                if (!"run".equals(method.getName()) || method.getParameterTypes().length != 0) {
                    continue;
                }
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        scheduleRunFallbackEnforcerStart(classLoader);
                    }
                });
                Log.i(TAG, "hooked SystemServer.run as fallback");
                systemLog("已 hook SystemServer.run 作为兜底入口");
                return true;
            }
            systemLog("hook SystemServer.run 失败: 未找到无参 run() 重载");
            return false;
        } catch (Throwable failure) {
            Log.w(TAG, "unable to hook SystemServer.run: "
                    + failure.getClass().getSimpleName());
            systemLog("hook SystemServer.run 失败: " + failure.getClass().getSimpleName()
                    + ": " + failure.getMessage());
            return false;
        }
    }

    private static void scheduleRunFallbackEnforcerStart(final ClassLoader classLoader) {
        try {
            final Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.postDelayed(
                    () -> startEnforcer(null, classLoader, "run_fallback"),
                    RUN_FALLBACK_DELAY_MS);
            systemLog("已安排 run 兜底 enforcer 启动 (delay=" + RUN_FALLBACK_DELAY_MS + "ms)");
        } catch (Throwable failure) {
            systemLog("无法安排 run 兜底 enforcer 启动: "
                    + failure.getClass().getSimpleName());
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