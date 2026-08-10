package com.leaf.hyperdragshare.codex;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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

    private static final String SYSTEM_SERVER_CLASS = "com.android.server.SystemServer";
    private static final String TIMINGS_TRACE_AND_SLOG_CLASS =
            "com.android.server.utils.TimingsTraceAndSlog";
    private static final String BACKGROUND_THREAD_CLASS =
            "com.android.internal.os.BackgroundThread";

    private static volatile AccessibilityServiceEnforcer enforcer;

    private AccessibilityProtectionHooks() {}

    /** Called from MainHook when packageName is "android". */
    static void install(ClassLoader classLoader) {
        if (classLoader == null) {
            Log.w(TAG, "no system_server class loader for accessibility protection");
            return;
        }
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
                            startEnforcer(param.thisObject, classLoader);
                        }
                    });
            Log.i(TAG, "hooked SystemServer.startOtherServices");
        } catch (Throwable failure) {
            Log.w(TAG, "unable to install accessibility protection hook: "
                    + failure.getClass().getSimpleName());
        }
    }

    private static synchronized void startEnforcer(Object systemServer, ClassLoader classLoader) {
        if (enforcer != null) {
            return;
        }
        Context context = resolveSystemContext(systemServer);
        if (context == null) {
            Log.w(TAG, "SystemServer 已启动，但无法取得 system context");
            return;
        }
        Handler handler = resolveSystemBackgroundHandler(classLoader);
        if (handler == null) {
            Log.w(TAG, "无法取得 Android BackgroundThread，跳过无障碍保护");
            return;
        }
        AccessibilityServiceEnforcer enforcer = new AccessibilityServiceEnforcer(handler);
        AccessibilityProtectionHooks.enforcer = enforcer;
        enforcer.start(context);
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