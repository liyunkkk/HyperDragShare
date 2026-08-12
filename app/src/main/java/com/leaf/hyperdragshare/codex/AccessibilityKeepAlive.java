package com.leaf.hyperdragshare.codex;

import android.content.Context;

/**
 * Opt-in keeper that pushes the accessibility-protection switch to the
 * system_server backend. The backend runs for the lifetime of system_server
 * and re-enables the service even after the module process is force-stopped.
 * No alarms or receivers are needed in this process.
 */
public final class AccessibilityKeepAlive {
    private static final String TAG = "DragShare/KeepAlive";

    private AccessibilityKeepAlive() {}

    /**
     * Mirrors the current user switch to the system_server backend. The
     * backend is gated on its own trusted settings channel, so calling this
     * with a disabled switch disarms the protection in system_server too.
     */
    static void sync(Context context) {
        DragShareSettings settings = DragShareSettings.readLocal(context);
        boolean enabled = settings != null
                && settings.forceKeepAccessibilityEnabled
                && settings.isAccessibilityCaptureMode();
        AccessibilityProtectionClient.setEnabled(
                context,
                enabled,
                result -> {
                    if (result.status == AccessibilityProtectionClient.ControlStatus.UNAVAILABLE) {
                        DragShareLog.w(TAG, "系统服务器无障碍保护后端不可用（广播未获响应或后端写入失败）");
                    } else if (result.status == AccessibilityProtectionClient.ControlStatus.REJECTED) {
                        DragShareLog.w(TAG, "系统服务器无障碍保护拒绝本次请求（开关/协议/签名校验未通过）");
                    } else {
                        DragShareLog.i(TAG, "系统服务器无障碍保护已同步: enabled=" + result.enabled);
                    }
                });
    }

    static MergeResult mergeEnabledServices(String current, String component) {
        String merged = AccessibilityServiceMerge.appendAccessibilityServiceIfMissing(
                current,
                component);
        return merged == null
                ? new MergeResult(current, false)
                : new MergeResult(merged, true);
    }

    static final class MergeResult {
        final String value;
        final boolean changed;

        MergeResult(String value, boolean changed) {
            this.value = value;
            this.changed = changed;
        }
    }
}