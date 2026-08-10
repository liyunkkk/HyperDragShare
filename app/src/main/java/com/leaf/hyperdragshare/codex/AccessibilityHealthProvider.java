package com.leaf.hyperdragshare.codex;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/**
 * Exposes a minimal service-connection state to system_server. It never returns
 * node trees, windows or user content.
 */
public final class AccessibilityHealthProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID
                || !AccessibilityProtectionProtocol.HEALTH_METHOD.equals(method)
                || !AccessibilityProtectionProtocol.hasSupportedVersion(extras)) {
            return AccessibilityProtectionProtocol.healthResult(
                    AccessibilityProtectionProtocol.HEALTH_STATUS_REJECTED);
        }
        return AccessibilityProtectionProtocol.healthResult(
                AccessibilityRuntimeStatus.isConnected()
                        ? AccessibilityProtectionProtocol.HEALTH_STATUS_CONNECTED
                        : AccessibilityProtectionProtocol.HEALTH_STATUS_DISCONNECTED);
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(
            Uri uri,
            ContentValues values,
            String selection,
            String[] selectionArgs) {
        return 0;
    }
}