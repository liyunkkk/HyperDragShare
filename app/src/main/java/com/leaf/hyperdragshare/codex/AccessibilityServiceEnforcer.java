package com.leaf.hyperdragshare.codex;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the module accessibility service available for the lifetime of
 * system_server according to the user's selection.
 *
 * Protection is off by default. When enabled it only maintains the module
 * component and the master switch in the owner user, always preserving other
 * services. All work reuses Android's shared BackgroundThread; no extra threads
 * are created and nothing is polled periodically.
 */
final class AccessibilityServiceEnforcer {
    private static final String APP_PACKAGE = "com.leaf.hyperdragshare.codex";
    private static final String SERVICE_CLASS =
            "com.leaf.hyperdragshare.codex.DragShareAccessibilityService";
    private static final int DISABLED = 0;
    private static final int ENABLED = 1;
    private static final long LOG_INTERVAL_MS = 10_000L;
    private static final long SERVICE_REBIND_GRACE_MS = 4_000L;
    private static final long[] REGISTRATION_RETRY_DELAYS_MS =
            new long[] {1_000L, 5_000L, 30_000L};

    private static final ComponentName SERVICE_COMPONENT =
            new ComponentName(APP_PACKAGE, SERVICE_CLASS);
    private static final Uri CONTROL_SETTING_URI =
            Settings.Global.getUriFor(AccessibilityProtectionProtocol.SETTING_NAME);
    private static final Uri APP_SIGNER_URI =
            Settings.Global.getUriFor(AccessibilityProtectionProtocol.SIGNER_SETTING_NAME);
    private static final List<Uri> ACTIVE_SETTING_URIS;
    static {
        List<Uri> uris = new ArrayList<>();
        uris.add(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
        uris.add(Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED));
        uris.add(APP_SIGNER_URI);
        ACTIVE_SETTING_URIS = Collections.unmodifiableList(uris);
    }

    private final Handler handler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean workScheduled = new AtomicBoolean();
    private final AtomicBoolean rerunRequested = new AtomicBoolean();
    private final AtomicBoolean registrationRetryScheduled = new AtomicBoolean();
    private final AtomicBoolean healthCheckScheduled = new AtomicBoolean();
    private final AtomicBoolean runtimeRecoveryScheduled = new AtomicBoolean();
    private final AtomicBoolean repairInProgress = new AtomicBoolean();
    private final AccessibilityRestoreBackoff restoreBackoff = new AccessibilityRestoreBackoff();
    private final AccessibilityRepairLimiter repairLimiter = new AccessibilityRepairLimiter();
    private final Set<Uri> activeSettingUris = new HashSet<>();

    private ContentObserver controlSettingObserver;
    private ContentObserver activeSettingsObserver;
    private BroadcastReceiver controlReceiver;
    private BroadcastReceiver packageReceiver;
    private BroadcastReceiver lifecycleReceiver;
    private boolean controlSettingObserverRegistered;
    private boolean controlReceiverRegistered;
    private boolean packageReceiverRegistered;
    private boolean lifecycleReceiverRegistered;
    private int registrationRetryIndex;

    private volatile long lastRestoreLogAt;

    private static final String TAG = "DragShare/Enforcer";
    private static final String SYSTEM_LOG_DIRECTORY = "/data/local/tmp/HyperDragShare";
    private static final String SYSTEM_LOG_FILE = SYSTEM_LOG_DIRECTORY + "/system-server.log";

    AccessibilityServiceEnforcer(Handler handler) {
        this.handler = handler;
    }

    void start(Context context) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        systemLog("enforcer start() 被调用，开始调度 reconcile");
        if (!schedule(context, "system_ready", 0L)) {
            started.set(false);
            systemLog("enforcer start() 调度失败，标记为未启动");
        }
    }

    private void reconcile(Context context, String reason) {
        boolean enabled = isEnforcementEnabled(context);
        ensureObserversRegistered(context, enabled);
        if (!enabled) {
            restoreBackoff.reset();
            repairLimiter.reset();
            return;
        }
        if (repairInProgress.get()) {
            return;
        }
        enforce(context, reason);
        if (shouldScheduleAccessibilityHealthCheck(
                isOwnerUnlocked(context),
                isExpectedServiceConfigured(context))) {
            scheduleHealthCheck(context, reason, SERVICE_REBIND_GRACE_MS);
        }
    }

    private void ensureObserversRegistered(
            Context context,
            boolean enforcementEnabled) {
        ensureControlChannelRegistered(context);
        if (enforcementEnabled) {
            ensureActiveObserversRegistered(context);
        } else {
            unregisterActiveObservers(context);
        }

        boolean controlReady = controlSettingObserverRegistered && controlReceiverRegistered;
        boolean activeReady;
        if (enforcementEnabled) {
            activeReady = activeSettingUris.size() == ACTIVE_SETTING_URIS.size()
                    && packageReceiverRegistered
                    && lifecycleReceiverRegistered;
        } else {
            activeReady = activeSettingUris.isEmpty()
                    && !packageReceiverRegistered
                    && !lifecycleReceiverRegistered;
        }
        if (controlReady && activeReady) {
            registrationRetryIndex = 0;
        } else {
            scheduleRegistrationRetry(context);
        }
    }

    private void ensureControlChannelRegistered(Context context) {
        if (controlSettingObserver == null) {
            controlSettingObserver = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange) {
                    schedule(context, "control_setting_changed", 0L);
                }

                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    schedule(context, "control_setting_changed", 0L);
                }
            };
        }
        if (!controlSettingObserverRegistered) {
            try {
                context.getContentResolver().registerContentObserver(
                        CONTROL_SETTING_URI,
                        false,
                        controlSettingObserver);
                controlSettingObserverRegistered = true;
            } catch (RuntimeException failure) {
                logFailure("无法监听无障碍保护开关", failure);
                systemLog("注册开关 ContentObserver 失败: "
                        + failure.getClass().getSimpleName());
            }
        }
        if (!controlReceiverRegistered) {
            if (controlReceiver == null) {
                controlReceiver = createControlReceiver();
            }
            try {
                IntentFilter filter = new IntentFilter();
                filter.addAction(AccessibilityProtectionProtocol.ACTION_SET);
                filter.addAction(AccessibilityProtectionProtocol.ACTION_RECOVER);
                context.registerReceiver(
                        controlReceiver,
                        filter,
                        AccessibilityProtectionProtocol.PERMISSION,
                        handler,
                        Context.RECEIVER_EXPORTED);
                controlReceiverRegistered = true;
                systemLog("控制广播 receiver 已注册（签名权限保护）");
            } catch (RuntimeException failure) {
                logFailure("无法注册无障碍保护控制入口", failure);
                systemLog("注册控制广播 receiver 失败: "
                        + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
        }
    }

    private void ensureActiveObserversRegistered(Context context) {
        if (activeSettingsObserver == null) {
            activeSettingsObserver = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange) {
                    schedule(context, "accessibility_settings_changed", null);
                }

                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    schedule(
                            context,
                            APP_SIGNER_URI.equals(uri)
                                    ? "signer_setting_changed"
                                    : "accessibility_settings_changed",
                            APP_SIGNER_URI.equals(uri) ? 0L : null);
                }
            };
        }
        ContentResolver resolver = context.getContentResolver();
        for (Uri uri : ACTIVE_SETTING_URIS) {
            if (activeSettingUris.contains(uri)) {
                continue;
            }
            try {
                resolver.registerContentObserver(uri, false, activeSettingsObserver);
                activeSettingUris.add(uri);
            } catch (RuntimeException failure) {
                logFailure("无法监听无障碍设置", failure);
            }
        }
        if (!packageReceiverRegistered) {
            if (packageReceiver == null) {
                packageReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context receiverContext, Intent intent) {
                        if (intent != null
                                && intent.getData() != null
                                && APP_PACKAGE.equals(intent.getData().getSchemeSpecificPart())) {
                            schedule(receiverContext, "package_changed", null);
                        }
                    }
                };
            }
            try {
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_PACKAGE_ADDED);
                filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
                filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
                filter.addDataScheme("package");
                context.registerReceiver(
                        packageReceiver,
                        filter,
                        null,
                        handler,
                        Context.RECEIVER_NOT_EXPORTED);
                packageReceiverRegistered = true;
            } catch (RuntimeException failure) {
                logFailure("无法监听模块包变化", failure);
            }
        }
        if (!lifecycleReceiverRegistered) {
            if (lifecycleReceiver == null) {
                lifecycleReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context receiverContext, Intent intent) {
                        String action = intent == null ? null : intent.getAction();
                        String reason;
                        if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                            reason = "owner_unlocked";
                        } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                            reason = "owner_present";
                        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
                            reason = "boot_completed";
                        } else {
                            reason = "locked_boot_completed";
                        }
                        schedule(receiverContext, reason, 0L);
                    }
                };
            }
            try {
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED);
                filter.addAction(Intent.ACTION_BOOT_COMPLETED);
                filter.addAction(Intent.ACTION_USER_UNLOCKED);
                filter.addAction(Intent.ACTION_USER_PRESENT);
                context.registerReceiver(
                        lifecycleReceiver,
                        filter,
                        null,
                        handler,
                        Context.RECEIVER_NOT_EXPORTED);
                lifecycleReceiverRegistered = true;
            } catch (RuntimeException failure) {
                logFailure("无法监听 owner 用户生命周期", failure);
            }
        }
    }

    private void unregisterActiveObservers(Context context) {
        if (!activeSettingUris.isEmpty()) {
            try {
                if (activeSettingsObserver != null) {
                    context.getContentResolver().unregisterContentObserver(
                            activeSettingsObserver);
                }
                activeSettingUris.clear();
            } catch (RuntimeException failure) {
                logFailure("无法停止监听无障碍设置", failure);
            }
        }
        if (packageReceiverRegistered) {
            try {
                if (packageReceiver != null) {
                    context.unregisterReceiver(packageReceiver);
                }
                packageReceiverRegistered = false;
            } catch (RuntimeException failure) {
                logFailure("无法注销模块包监听", failure);
            }
        }
        if (lifecycleReceiverRegistered) {
            try {
                if (lifecycleReceiver != null) {
                    context.unregisterReceiver(lifecycleReceiver);
                }
                lifecycleReceiverRegistered = false;
            } catch (RuntimeException failure) {
                logFailure("无法注销 owner 生命周期监听", failure);
            }
        }
    }

    private static int senderUidOf(BroadcastReceiver receiver) {
        try {
            Method getSentFromUid = BroadcastReceiver.class.getMethod("getSentFromUid");
            return ((Integer) getSentFromUid.invoke(receiver)).intValue();
        } catch (NoSuchMethodException notFound) {
            try {
                Method getSendingUid = BroadcastReceiver.class.getMethod("getSendingUid");
                return ((Integer) getSendingUid.invoke(receiver)).intValue();
            } catch (Throwable failure) {
                return Process.SYSTEM_UID;
            }
        } catch (Throwable failure) {
            return Process.SYSTEM_UID;
        }
    }

    private BroadcastReceiver createControlReceiver() {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                final int senderUid = senderUidOf(this);
                final boolean ordered = isOrderedBroadcast();
                final String action = intent == null ? null : intent.getAction();
                final int protocolVersion = intent == null
                        ? -1
                        : intent.getIntExtra(
                        AccessibilityProtectionProtocol.EXTRA_PROTOCOL_VERSION,
                        -1);
                final boolean requestedEnabled = intent != null
                        && intent.getBooleanExtra(
                        AccessibilityProtectionProtocol.EXTRA_ENABLED,
                        AccessibilityProtectionProtocol.DEFAULT_ENABLED);
                systemLog("收到控制广播 action=" + action
                        + " senderUid=" + senderUid
                        + " ordered=" + ordered
                        + " protocol=" + protocolVersion
                        + " requestedEnabled=" + requestedEnabled);
                if (!ordered) {
                    systemLog("控制广播非有序，忽略本次请求");
                    return;
                }
                final BroadcastReceiver.PendingResult pendingResult = goAsync();
                final boolean posted = post(() -> {
                    int resultCode = AccessibilityProtectionProtocol.RESULT_UNAVAILABLE;
                    boolean actualEnabled = false;
                    try {
                        resultCode = applyControlRequest(
                                receiverContext,
                                action,
                                ordered,
                                protocolVersion,
                                senderUid,
                                requestedEnabled);
                        actualEnabled = isEnforcementEnabled(receiverContext);
                    } catch (RuntimeException failure) {
                        logFailure("无障碍保护控制请求失败", failure);
                    }
                    systemLog("控制请求处理完成 action=" + action
                            + " resultCode=" + resultCode
                            + " actualEnabled=" + actualEnabled);
                    completeControlRequest(pendingResult, resultCode, actualEnabled);
                });
                if (!posted) {
                    systemLog("控制请求无法投递到后台 Handler，返回不可用");
                    completeControlRequest(
                            pendingResult,
                            AccessibilityProtectionProtocol.RESULT_UNAVAILABLE,
                            false);
                }
            }
        };
    }

    private int applyControlRequest(
            Context context,
            String action,
            boolean ordered,
            int protocolVersion,
            int senderUid,
            boolean requestedEnabled) {
        if (!isControlAction(action)
                || !isControlCallerTrusted(context, ordered, protocolVersion, senderUid)) {
            return AccessibilityProtectionProtocol.RESULT_REJECTED;
        }
        if (AccessibilityProtectionProtocol.ACTION_RECOVER.equals(action)) {
            if (!isEnforcementEnabled(context)) {
                return AccessibilityProtectionProtocol.RESULT_UNAVAILABLE;
            }
            if (!isExpectedServiceTrusted(context)) {
                return AccessibilityProtectionProtocol.RESULT_REJECTED;
            }
            return scheduleRuntimeRecovery(context)
                    ? AccessibilityProtectionProtocol.RESULT_APPLIED
                    : AccessibilityProtectionProtocol.RESULT_UNAVAILABLE;
        }
        if (requestedEnabled && !isExpectedServiceTrusted(context)) {
            return AccessibilityProtectionProtocol.RESULT_REJECTED;
        }
        boolean stored;
        try {
            stored = Settings.Global.putInt(
                    context.getContentResolver(),
                    AccessibilityProtectionProtocol.SETTING_NAME,
                    requestedEnabled ? ENABLED : DISABLED);
        } catch (RuntimeException failure) {
            logFailure("无法写入无障碍保护开关", failure);
            return AccessibilityProtectionProtocol.RESULT_UNAVAILABLE;
        }
        if (!stored) {
            return AccessibilityProtectionProtocol.RESULT_UNAVAILABLE;
        }
        restoreBackoff.reset();
        reconcile(context, "user_control");
        Log.i(TAG, "无障碍保护开关已设置为 " + requestedEnabled);
        return AccessibilityProtectionProtocol.RESULT_APPLIED;
    }

    private boolean scheduleRuntimeRecovery(Context context) {
        if (!runtimeRecoveryScheduled.compareAndSet(false, true)) {
            return true;
        }
        boolean posted = post(() -> {
            runtimeRecoveryScheduled.set(false);
            try {
                verifyServiceConnection(context, "runtime_unavailable", true);
            } catch (RuntimeException failure) {
                logFailure("Runtime 请求的无障碍恢复失败", failure);
            }
        });
        if (!posted) {
            runtimeRecoveryScheduled.set(false);
        }
        return posted;
    }

    private void completeControlRequest(
            BroadcastReceiver.PendingResult pendingResult,
            int resultCode,
            boolean enabled) {
        try {
            pendingResult.setResultCode(resultCode);
            Bundle extras = new Bundle();
            extras.putBoolean(AccessibilityProtectionProtocol.EXTRA_ENABLED, enabled);
            pendingResult.setResultExtras(extras);
        } catch (RuntimeException failure) {
            logFailure("无法返回无障碍保护控制结果", failure);
        } finally {
            pendingResult.finish();
        }
    }

    private void scheduleRegistrationRetry(Context context) {
        if (registrationRetryIndex >= REGISTRATION_RETRY_DELAYS_MS.length
                || !registrationRetryScheduled.compareAndSet(false, true)) {
            return;
        }
        final long delayMs = REGISTRATION_RETRY_DELAYS_MS[registrationRetryIndex++];
        boolean posted = post(() -> {
            registrationRetryScheduled.set(false);
            schedule(context, "observer_registration_retry", 0L);
        }, delayMs);
        if (!posted) {
            registrationRetryScheduled.set(false);
            logFailure("无法调度无障碍监听注册重试");
        }
    }

    private void scheduleHealthCheck(Context context, String reason, long delayMs) {
        if (!healthCheckScheduled.compareAndSet(false, true)) {
            return;
        }
        boolean posted = post(() -> {
            healthCheckScheduled.set(false);
            try {
                verifyServiceConnection(context, reason, false);
            } catch (RuntimeException failure) {
                logFailure("无障碍连接检查失败", failure);
            }
        }, delayMs);
        if (!posted) {
            healthCheckScheduled.set(false);
            logFailure("无法调度无障碍连接检查");
        }
    }

    private void verifyServiceConnection(
            Context context,
            String reason,
            boolean restoreMissingImmediately) {
        if (!isEnforcementEnabled(context)
                || repairInProgress.get()
                || !isOwnerUnlocked(context)
                || !isExpectedServiceTrusted(context)) {
            return;
        }
        String currentServices = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (!AccessibilityServiceMerge.containsAccessibilityService(
                currentServices,
                SERVICE_COMPONENT.flattenToString())) {
            if (restoreMissingImmediately) {
                enforce(context, "runtime_recovery_missing_service");
                if (isExpectedServiceConfigured(context)) {
                    scheduleHealthCheck(
                            context,
                            "runtime_recovery_setting_restored",
                            SERVICE_REBIND_GRACE_MS);
                }
            } else {
                schedule(context, "health_check_missing_service", 0L);
            }
            return;
        }
        switch (queryServiceConnection(context)) {
            case CONNECTED:
                repairLimiter.reset();
                break;
            case DISCONNECTED: {
                AccessibilityRepairAttempt attempt =
                        repairLimiter.nextAttempt(SystemClock.elapsedRealtime());
                if (attempt == null) {
                    logFailure("无障碍服务连续重绑失败，已进入冷却");
                } else {
                    beginServiceRepair(context, attempt, reason);
                }
                break;
            }
            case UNKNOWN:
            default:
                logFailure("无法确认无障碍服务连接状态");
                break;
        }
    }

    private AccessibilityConnectionStatus queryServiceConnection(Context context) {
        Bundle response;
        try {
            response = context.getContentResolver().call(
                    AccessibilityProtectionProtocol.healthUri(),
                    AccessibilityProtectionProtocol.HEALTH_METHOD,
                    null,
                    AccessibilityProtectionProtocol.request());
        } catch (RuntimeException failure) {
            logFailure("无障碍健康检查 Provider 调用失败", failure);
            return AccessibilityConnectionStatus.UNKNOWN;
        }
        if (response == null
                || response.getInt(
                AccessibilityProtectionProtocol.EXTRA_PROTOCOL_VERSION,
                -1) != AccessibilityProtectionProtocol.VERSION) {
            return AccessibilityConnectionStatus.UNKNOWN;
        }
        String status = response.getString(AccessibilityProtectionProtocol.HEALTH_STATUS);
        if (AccessibilityProtectionProtocol.HEALTH_STATUS_CONNECTED.equals(status)) {
            return AccessibilityConnectionStatus.CONNECTED;
        }
        if (AccessibilityProtectionProtocol.HEALTH_STATUS_DISCONNECTED.equals(status)) {
            return AccessibilityConnectionStatus.DISCONNECTED;
        }
        return AccessibilityConnectionStatus.UNKNOWN;
    }

    private void beginServiceRepair(
            Context context,
            AccessibilityRepairAttempt attempt,
            String reason) {
        if (!repairInProgress.compareAndSet(false, true)) {
            return;
        }
        ContentResolver resolver = context.getContentResolver();
        String servicesWithoutTarget = removeLatestAccessibilitySetting(resolver);
        if (servicesWithoutTarget == null) {
            repairInProgress.set(false);
            schedule(context, "repair_target_missing", 0L);
            return;
        }
        if (!Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                servicesWithoutTarget)) {
            repairInProgress.set(false);
            logFailure("无法临时关闭无障碍服务");
            return;
        }
        Log.i(TAG, "开始重绑无障碍服务: reason=" + reason
                + " attempt=" + attempt.number);
        boolean posted = post(
                () -> completeServiceRepair(context, attempt),
                attempt.disabledDurationMs);
        if (!posted) {
            repairInProgress.set(false);
            logFailure("无法调度无障碍服务重启");
            schedule(context, "repair_schedule_failed", 0L);
        }
    }

    private void completeServiceRepair(
            Context context,
            AccessibilityRepairAttempt attempt) {
        try {
            if (isEnforcementEnabled(context) && isExpectedServiceTrusted(context)) {
                enforce(context, "repair_" + attempt.number);
            }
        } catch (RuntimeException failure) {
            logFailure("无法重新启用无障碍服务", failure);
        } finally {
            repairInProgress.set(false);
        }
        if (isEnforcementEnabled(context)) {
            scheduleHealthCheck(
                    context,
                    "repair_" + attempt.number + "_confirmation",
                    SERVICE_REBIND_GRACE_MS);
        }
    }

    private boolean schedule(
            Context context,
            String reason,
            Long delayMs) {
        rerunRequested.set(true);
        if (!workScheduled.compareAndSet(false, true)) {
            return true;
        }
        long resolvedDelay = delayMs != null
                ? delayMs
                : restoreBackoff.delayFor(SystemClock.elapsedRealtime());
        boolean posted = post(() -> {
            rerunRequested.set(false);
            try {
                reconcile(context, reason);
            } catch (RuntimeException failure) {
                logFailure("无障碍保护校验失败", failure);
            } finally {
                workScheduled.set(false);
                if (rerunRequested.get()) {
                    schedule(context, "late_change", null);
                }
            }
        }, resolvedDelay);
        if (!posted) {
            workScheduled.set(false);
            logFailure("无法调度无障碍保护校验");
        }
        return posted;
    }

    private void enforce(Context context, String reason) {
        if (!isEnforcementEnabled(context) || !isExpectedServiceTrusted(context)) {
            return;
        }
        ContentResolver resolver = context.getContentResolver();
        String mergedServices = mergeLatestAccessibilitySetting(resolver);
        boolean restoredServices = false;
        boolean restoredMasterSwitch = false;
        if (mergedServices != null) {
            restoredServices = Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    mergedServices);
            if (!restoredServices) {
                logFailure("无法恢复无障碍服务列表");
            }
        }
        boolean serviceIsEnabled = mergedServices == null || restoredServices;
        if (serviceIsEnabled && Settings.Secure.getInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                DISABLED) != ENABLED) {
            restoredMasterSwitch = Settings.Secure.putInt(
                    resolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    ENABLED);
            if (!restoredMasterSwitch) {
                logFailure("无法恢复无障碍总开关");
            }
        }
        if (restoredServices || restoredMasterSwitch) {
            restoreBackoff.recordRestore(SystemClock.elapsedRealtime());
            logRestore(reason, restoredServices, restoredMasterSwitch);
        }
    }

    private boolean isEnforcementEnabled(Context context) {
        int defaultValue = AccessibilityProtectionProtocol.DEFAULT_ENABLED ? 1 : 0;
        return Settings.Global.getInt(
                context.getContentResolver(),
                AccessibilityProtectionProtocol.SETTING_NAME,
                defaultValue) == ENABLED;
    }

    private boolean isOwnerUnlocked(Context context) {
        try {
            UserManager manager = (UserManager) context.getSystemService(
                    Context.USER_SERVICE);
            return manager != null && manager.isUserUnlocked();
        } catch (RuntimeException failure) {
            logFailure("无法读取 owner 用户状态", failure);
            return false;
        }
    }

    private boolean isExpectedServiceConfigured(Context context) {
        ContentResolver resolver = context.getContentResolver();
        if (Settings.Secure.getInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                DISABLED) != ENABLED) {
            return false;
        }
        return AccessibilityServiceMerge.containsAccessibilityService(
                Settings.Secure.getString(
                        resolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                SERVICE_COMPONENT.flattenToString());
    }

    private String mergeLatestAccessibilitySetting(ContentResolver resolver) {
        String initialValue = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String component = SERVICE_COMPONENT.flattenToString();
        if (AccessibilityServiceMerge.appendAccessibilityServiceIfMissing(
                initialValue,
                component) == null) {
            return null;
        }
        return AccessibilityServiceMerge.appendAccessibilityServiceIfMissing(
                Settings.Secure.getString(
                        resolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                component);
    }

    private String removeLatestAccessibilitySetting(ContentResolver resolver) {
        String initialValue = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String component = SERVICE_COMPONENT.flattenToString();
        if (AccessibilityServiceMerge.removeAccessibilityServiceIfPresent(
                initialValue,
                component) == null) {
            return null;
        }
        return AccessibilityServiceMerge.removeAccessibilityServiceIfPresent(
                Settings.Secure.getString(
                        resolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                component);
    }

    private boolean isExpectedServiceTrusted(Context context) {
        int directBootFlags = directBootFlags();
        ServiceInfo serviceInfo;
        try {
            serviceInfo = context.getPackageManager().getServiceInfo(
                    SERVICE_COMPONENT,
                    PackageManager.ComponentInfoFlags.of(directBootFlags));
        } catch (PackageManager.NameNotFoundException notFound) {
            return false;
        } catch (RuntimeException failure) {
            logFailure("无法校验无障碍服务组件", failure);
            return false;
        }
        if (serviceInfo == null || serviceInfo.applicationInfo == null) {
            return false;
        }
        boolean validComponent = serviceInfo.enabled
                && serviceInfo.applicationInfo.enabled
                && serviceInfo.exported
                && serviceInfo.permission != null
                && serviceInfo.permission.equals(
                android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE);
        return validComponent && hasTrustedSigningIdentity(context, directBootFlags);
    }

    private boolean isControlCallerTrusted(
            Context context,
            boolean ordered,
            int protocolVersion,
            int senderUid) {
        int directBootFlags = directBootFlags();
        ApplicationInfo applicationInfo;
        try {
            applicationInfo = context.getPackageManager().getApplicationInfo(
                    APP_PACKAGE,
                    PackageManager.ApplicationInfoFlags.of(directBootFlags));
        } catch (PackageManager.NameNotFoundException notFound) {
            return false;
        } catch (RuntimeException failure) {
            logFailure("无法校验无障碍保护控制调用方", failure);
            return false;
        }
        return applicationInfo != null
                && applicationInfo.enabled
                && AccessibilityServiceMerge.isAccessibilityControlRequestValid(
                ordered,
                protocolVersion,
                senderUid,
                applicationInfo.uid)
                && hasTrustedSigningIdentity(context, directBootFlags);
    }

    private int directBootFlags() {
        return PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
    }

    private boolean hasTrustedSigningIdentity(Context context, int directBootFlags) {
        android.content.pm.PackageInfo packageInfo;
        try {
            packageInfo = context.getPackageManager().getPackageInfo(
                    APP_PACKAGE,
                    PackageManager.PackageInfoFlags.of(
                            directBootFlags | PackageManager.GET_SIGNING_CERTIFICATES));
        } catch (PackageManager.NameNotFoundException notFound) {
            return false;
        } catch (RuntimeException failure) {
            logFailure("无法读取模块 APK signer", failure);
            return false;
        }
        if (packageInfo == null || packageInfo.signingInfo == null) {
            return false;
        }
        SigningInfo signingInfo = packageInfo.signingInfo;
        List<String> currentDigests = signerDigests(signingInfo.getApkContentsSigners());
        if (currentDigests.isEmpty()) {
            return false;
        }
        List<String> historyDigests;
        if (signingInfo.hasMultipleSigners()) {
            historyDigests = currentDigests;
        } else {
            historyDigests = signerDigests(signingInfo.getSigningCertificateHistory());
            if (historyDigests.isEmpty()) {
                historyDigests = currentDigests;
            }
        }
        String pinnedSigner = Settings.Global.getString(
                context.getContentResolver(),
                AccessibilityProtectionProtocol.SIGNER_SETTING_NAME);
        if (pinnedSigner == null || pinnedSigner.trim().isEmpty()) {
            String identity = AccessibilityServiceMerge.signerIdentity(currentDigests);
            if (identity == null) {
                return false;
            }
            try {
                if (!Settings.Global.putString(
                        context.getContentResolver(),
                        AccessibilityProtectionProtocol.SIGNER_SETTING_NAME,
                        identity)) {
                    logFailure("无法钉扎模块 APK signer");
                    return false;
                }
            } catch (RuntimeException failure) {
                logFailure("无法钉扎模块 APK signer", failure);
                return false;
            }
            Log.i(TAG, "已钉扎模块 APK signer");
            return true;
        }
        boolean accepted = AccessibilityServiceMerge.isPinnedSignerAccepted(
                pinnedSigner,
                currentDigests,
                historyDigests,
                signingInfo.hasMultipleSigners());
        if (!accepted) {
            logFailure("拒绝为 signer 不匹配的模块恢复无障碍权限");
        }
        return accepted;
    }

    private List<String> signerDigests(Signature[] signatures) {
        if (signatures == null || signatures.length == 0) {
            return Collections.emptyList();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> digests = new ArrayList<>(signatures.length);
            for (Signature signature : signatures) {
                digests.add(toLowerHex(digest.digest(signature.toByteArray())));
            }
            return digests;
        } catch (GeneralSecurityException failure) {
            logFailure("无法计算模块 APK signer", failure);
            return Collections.emptyList();
        }
    }

    private static String toLowerHex(byte[] bytes) {
        String digits = "0123456789abcdef";
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            int current = value & 0xff;
            builder.append(digits.charAt(current >>> 4));
            builder.append(digits.charAt(current & 0x0f));
        }
        return builder.toString();
    }

    private boolean post(Runnable block) {
        return post(block, 0L);
    }

    private boolean post(Runnable block, long delayMs) {
        try {
            return handler.postDelayed(block, delayMs);
        } catch (RuntimeException failure) {
            Log.w(TAG, "无障碍保护后台 Handler 拒绝任务: type="
                    + failure.getClass().getSimpleName());
            return false;
        }
    }

    private static void systemLog(String message) {
        try {
            File directory = new File(SYSTEM_LOG_DIRECTORY);
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

    private void logFailure(String message) {
        logFailure(message, null);
    }

    private void logFailure(String message, Throwable failure) {
        if (failure == null) {
            Log.w(TAG, message);
        } else {
            Log.w(TAG, message + ": type=" + failure.getClass().getSimpleName());
        }
    }

    private void logRestore(
            String reason,
            boolean restoredServices,
            boolean restoredMasterSwitch) {
        long now = SystemClock.elapsedRealtime();
        if (lastRestoreLogAt != 0L && now - lastRestoreLogAt < LOG_INTERVAL_MS) {
            return;
        }
        lastRestoreLogAt = now;
        Log.i(TAG, "已恢复无障碍服务: reason=" + reason
                + " serviceList=" + restoredServices
                + " masterSwitch=" + restoredMasterSwitch);
    }

    private static boolean isControlAction(String action) {
        return AccessibilityProtectionProtocol.ACTION_SET.equals(action)
                || AccessibilityProtectionProtocol.ACTION_RECOVER.equals(action);
    }

    private static boolean shouldScheduleAccessibilityHealthCheck(
            boolean ownerUnlocked,
            boolean serviceConfigured) {
        return ownerUnlocked && serviceConfigured;
    }

    private enum AccessibilityConnectionStatus {
        CONNECTED,
        DISCONNECTED,
        UNKNOWN,
    }

    static final class AccessibilityRepairAttempt {
        final int number;
        final long disabledDurationMs;

        AccessibilityRepairAttempt(int number, long disabledDurationMs) {
            this.number = number;
            this.disabledDurationMs = disabledDurationMs;
        }
    }

    static final class AccessibilityRepairLimiter {
        private final long[] disabledDurationsMs;
        private final long cooldownMs;
        private int attempts;
        private Long lastAttemptAt;

        AccessibilityRepairLimiter(
                long[] disabledDurationsMs,
                long cooldownMs) {
            this.disabledDurationsMs = disabledDurationsMs.clone();
            this.cooldownMs = cooldownMs;
        }

        AccessibilityRepairLimiter() {
            this(new long[] {500L, 1_000L, 2_000L}, 60_000L);
        }

        synchronized AccessibilityRepairAttempt nextAttempt(long now) {
            Long lastAttempt = lastAttemptAt;
            if (attempts >= disabledDurationsMs.length
                    && lastAttempt != null
                    && now - lastAttempt < cooldownMs) {
                return null;
            }
            if (attempts >= disabledDurationsMs.length) {
                attempts = 0;
            }
            AccessibilityRepairAttempt attempt = new AccessibilityRepairAttempt(
                    attempts + 1,
                    disabledDurationsMs[attempts]);
            attempts += 1;
            lastAttemptAt = now;
            return attempt;
        }

        synchronized void reset() {
            attempts = 0;
            lastAttemptAt = null;
        }
    }

    static final class AccessibilityRestoreBackoff {
        private final long[] delaysMs;
        private final long stableWindowMs;
        private int level;
        private Long lastRestoreAt;

        AccessibilityRestoreBackoff(
                long[] delaysMs,
                long stableWindowMs) {
            this.delaysMs = delaysMs.clone();
            this.stableWindowMs = stableWindowMs;
        }

        AccessibilityRestoreBackoff() {
            this(new long[] {300L, 1_000L, 5_000L, 30_000L}, 60_000L);
        }

        synchronized long delayFor(long now) {
            resetIfStable(now);
            return delaysMs[Math.min(level, delaysMs.length - 1)];
        }

        synchronized void recordRestore(long now) {
            resetIfStable(now);
            level = Math.min(level + 1, delaysMs.length - 1);
            lastRestoreAt = now;
        }

        synchronized void reset() {
            level = 0;
            lastRestoreAt = null;
        }

        private void resetIfStable(long now) {
            Long previous = lastRestoreAt;
            if (previous == null) {
                return;
            }
            if (now >= previous && now - previous >= stableWindowMs) {
                level = 0;
                lastRestoreAt = null;
            }
        }
    }
}