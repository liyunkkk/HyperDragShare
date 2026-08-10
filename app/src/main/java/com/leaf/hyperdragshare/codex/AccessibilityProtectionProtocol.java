package com.leaf.hyperdragshare.codex;

import android.net.Uri;
import android.os.Bundle;

/**
 * Minimal protocol between the module app and the system_server accessibility
 * protection backend. The control broadcast is validated by a signature
 * permission, the sender UID and the APK signer; the health-check provider only
 * accepts the system UID. The protocol never grants the app write access to
 * Secure settings.
 */
final class AccessibilityProtectionProtocol {
    static final int VERSION = 1;

    static final String ACTION_SET =
            "com.leaf.hyperdragshare.codex.action.SET_ACCESSIBILITY_PROTECTION";
    static final String ACTION_RECOVER =
            "com.leaf.hyperdragshare.codex.action.RECOVER_ACCESSIBILITY_SERVICE";
    static final String PERMISSION =
            "com.leaf.hyperdragshare.codex.permission.CONTROL_ACCESSIBILITY_PROTECTION";
    static final String RECEIVER_PACKAGE = "android";

    static final String EXTRA_PROTOCOL_VERSION = "protocol_version";
    static final String EXTRA_ENABLED = "enabled";

    static final int RESULT_UNAVAILABLE = 0;
    static final int RESULT_APPLIED = 1;
    static final int RESULT_REJECTED = 2;

    static final String SETTING_NAME = "hyperdragshare_accessibility_protection_enabled";
    static final String SIGNER_SETTING_NAME = "hyperdragshare_app_signer_sha256";
    static final boolean DEFAULT_ENABLED = false;

    static final String HEALTH_AUTHORITY = "com.leaf.hyperdragshare.codex.accessibility.health";
    static final String HEALTH_METHOD = "accessibility_health";
    static final String HEALTH_STATUS = "status";
    static final String HEALTH_STATUS_CONNECTED = "connected";
    static final String HEALTH_STATUS_DISCONNECTED = "disconnected";
    static final String HEALTH_STATUS_REJECTED = "rejected";

    private static final Uri HEALTH_URI =
            Uri.parse("content://" + HEALTH_AUTHORITY);

    private AccessibilityProtectionProtocol() {}

    static Uri healthUri() {
        return HEALTH_URI;
    }

    static Bundle request() {
        Bundle extras = new Bundle();
        extras.putInt(EXTRA_PROTOCOL_VERSION, VERSION);
        return extras;
    }

    static boolean hasSupportedVersion(Bundle extras) {
        return extras != null && extras.getInt(EXTRA_PROTOCOL_VERSION, -1) == VERSION;
    }

    static Bundle healthResult(String status) {
        Bundle result = new Bundle();
        result.putInt(EXTRA_PROTOCOL_VERSION, VERSION);
        result.putString(HEALTH_STATUS, status);
        return result;
    }
}