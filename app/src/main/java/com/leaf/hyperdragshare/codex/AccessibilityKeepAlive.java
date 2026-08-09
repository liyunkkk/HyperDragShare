package com.leaf.hyperdragshare.codex;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;

/** Opt-in watchdog that re-enables the accessibility service through root when it is turned off. */
public final class AccessibilityKeepAlive {
    private static final String TAG = "DragShare/KeepAlive";
    private static final String ACTION_KEEP = "com.leaf.hyperdragshare.codex.action.KEEP_ACCESSIBILITY";
    private static final long CHECK_INTERVAL_MILLIS = 60_000L;

    private AccessibilityKeepAlive() {}

    /** Schedules the watchdog when the user opted in, otherwise cancels it and restores once. */
    static void sync(Context context) {
        if (context == null) {
            return;
        }
        DragShareSettings settings = DragShareSettings.readLocal(context);
        if (settings.forceKeepAccessibilityEnabled && settings.isAccessibilityCaptureMode()) {
            schedule(context);
            checkAndRestore(context);
        } else {
            cancel(context);
        }
    }

    private static void schedule(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }
        PendingIntent operation = pendingIntent(context);
        long triggerAt = SystemClock.elapsedRealtime() + CHECK_INTERVAL_MILLIS;
        manager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                operation);
    }

    private static void cancel(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }
        manager.cancel(pendingIntent(context));
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, KeepAliveReceiver.class);
        intent.setAction(ACTION_KEEP);
        return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void checkAndRestore(Context context) {
        DragShareSettings settings = DragShareSettings.readLocal(context);
        if (settings == null
                || !settings.forceKeepAccessibilityEnabled
                || !settings.isAccessibilityCaptureMode()) {
            return;
        }
        if (AccessibilityRuntimeStatus.isServiceEnabled(context)) {
            return;
        }
        final Context appContext = context.getApplicationContext() == null
                ? context
                : context.getApplicationContext();
        new Thread(() -> restoreOnBackground(appContext), "DragShare-KeepAlive").start();
    }

    private static void restoreOnBackground(Context context) {
        String component = new ComponentName(context, DragShareAccessibilityService.class)
                .flattenToShortString();
        String current;
        try {
            current = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        } catch (Throwable ignored) {
            current = null;
        }
        String merged = mergeEnabledServices(current, component).value;
        boolean listWritten = ModuleActivation.putSecureSetting(
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                merged);
        boolean flagWritten = ModuleActivation.putSecureSetting(
                Settings.Secure.ACCESSIBILITY_ENABLED,
                "1");
        if (listWritten && flagWritten) {
            DragShareLog.i(TAG, "accessibility service re-enabled");
        } else {
            DragShareLog.w(TAG, "accessibility re-enable failed");
        }
    }

    static MergeResult mergeEnabledServices(String current, String component) {
        if (current == null || current.trim().isEmpty()) {
            return new MergeResult(component, true);
        }
        for (String entry : current.split(":")) {
            if (component.equals(entry)) {
                return new MergeResult(current, false);
            }
        }
        return new MergeResult(current + ":" + component, true);
    }

    static final class MergeResult {
        final String value;
        final boolean changed;

        MergeResult(String value, boolean changed) {
            this.value = value;
            this.changed = changed;
        }
    }

    /** Re-arms the watchdog and restores the service; repeats until the user turns the feature off. */
    public static final class KeepAliveReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_KEEP.equals(intent.getAction())) {
                return;
            }
            sync(context);
        }
    }

    /** Restores the watchdog schedule after a reboot. */
    public static final class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                return;
            }
            sync(context);
        }
    }
}
