package com.leaf.hyperdragshare.codex;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The module app only expresses the protection switch and recovery requests;
 * Secure settings are always maintained by the system_server backend.
 */
final class AccessibilityProtectionClient {
    private static final String PREFERENCES_NAME = "accessibility_protection";
    private static final String PREFERENCE_ENABLED = "enabled";
    private static final long CONTROL_TIMEOUT_MS = 2_000L;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private AccessibilityProtectionClient() {}

    static boolean isEnabled(Context context) {
        Context appContext = context == null ? null : context.getApplicationContext();
        if (appContext == null) {
            return AccessibilityProtectionProtocol.DEFAULT_ENABLED;
        }
        boolean fallback = appContext
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(
                        PREFERENCE_ENABLED,
                        AccessibilityProtectionProtocol.DEFAULT_ENABLED);
        try {
            return Settings.Global.getInt(
                    appContext.getContentResolver(),
                    AccessibilityProtectionProtocol.SETTING_NAME,
                    fallback ? 1 : 0) == 1;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    static void setEnabled(
            Context context,
            boolean enabled,
            ControlCallback onResult) {
        if (context == null) {
            return;
        }
        final Context appContext = context.getApplicationContext() == null
                ? context
                : context.getApplicationContext();
        sendRequest(
                appContext,
                AccessibilityProtectionProtocol.ACTION_SET,
                enabled,
                MAIN_HANDLER,
                result -> {
                    if (result.status == ControlStatus.APPLIED) {
                        appContext
                                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean(PREFERENCE_ENABLED, result.enabled)
                                .apply();
                    }
                    if (onResult != null) {
                        onResult.onResult(result);
                    }
                });
    }

    static ControlStatus requestRecoveryBlocking(Context context) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return ControlStatus.UNAVAILABLE;
        }
        if (context == null) {
            return ControlStatus.UNAVAILABLE;
        }
        final Context appContext = context.getApplicationContext() == null
                ? context
                : context.getApplicationContext();
        final CountDownLatch latch = new CountDownLatch(1);
        final ControlStatus[] statusHolder = {ControlStatus.UNAVAILABLE};
        sendRequest(
                appContext,
                AccessibilityProtectionProtocol.ACTION_RECOVER,
                true,
                MAIN_HANDLER,
                result -> {
                    statusHolder[0] = result.status;
                    latch.countDown();
                });
        boolean completed;
        try {
            completed = latch.await(CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            completed = false;
        }
        return completed ? statusHolder[0] : ControlStatus.UNAVAILABLE;
    }

    private static void sendRequest(
            Context context,
            String action,
            boolean enabled,
            Handler scheduler,
            ControlCallback onResult) {
        Intent intent = new Intent(action)
                .setPackage(AccessibilityProtectionProtocol.RECEIVER_PACKAGE)
                .putExtra(
                        AccessibilityProtectionProtocol.EXTRA_PROTOCOL_VERSION,
                        AccessibilityProtectionProtocol.VERSION)
                .putExtra(AccessibilityProtectionProtocol.EXTRA_ENABLED, enabled);
        BroadcastReceiver resultReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                Bundle extras = getResultExtras(false);
                boolean actualEnabled = extras == null
                        ? isEnabled(context)
                        : extras.getBoolean(
                        AccessibilityProtectionProtocol.EXTRA_ENABLED,
                        isEnabled(context));
                onResult.onResult(new ControlResult(
                        toControlStatus(getResultCode()),
                        actualEnabled));
            }
        };

        try {
            if (android.os.Build.VERSION.SDK_INT
                    >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14 blocks sender identity from broadcast by default;
                // the backend must see the real UID to accept the request.
                Bundle options = BroadcastOptions.makeBasic()
                        .setShareIdentityEnabled(true)
                        .toBundle();
                context.sendOrderedBroadcast(
                        intent,
                        null,
                        options,
                        resultReceiver,
                        scheduler,
                        AccessibilityProtectionProtocol.RESULT_UNAVAILABLE,
                        null,
                        null);
            } else {
                context.sendOrderedBroadcast(
                        intent,
                        null,
                        resultReceiver,
                        scheduler,
                        AccessibilityProtectionProtocol.RESULT_UNAVAILABLE,
                        null,
                        null);
            }
        } catch (RuntimeException ignored) {
            scheduler.post(() -> onResult.onResult(new ControlResult(
                    ControlStatus.UNAVAILABLE,
                    isEnabled(context))));
        }
    }

    static final class ControlResult {
        final ControlStatus status;
        final boolean enabled;

        ControlResult(ControlStatus status, boolean enabled) {
            this.status = status;
            this.enabled = enabled;
        }
    }

    enum ControlStatus {
        APPLIED,
        UNAVAILABLE,
        REJECTED,
    }

    interface ControlCallback {
        void onResult(ControlResult result);
    }

    private static ControlStatus toControlStatus(int resultCode) {
        if (resultCode == AccessibilityProtectionProtocol.RESULT_APPLIED) {
            return ControlStatus.APPLIED;
        }
        if (resultCode == AccessibilityProtectionProtocol.RESULT_REJECTED) {
            return ControlStatus.REJECTED;
        }
        return ControlStatus.UNAVAILABLE;
    }
}