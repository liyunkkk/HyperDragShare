package com.leaf.hyperdragshare.codex;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AccessibilityServiceMergeTest {
    @Test
    public void acceptsModuleOwnUid() {
        assertTrue(AccessibilityServiceMerge.isAccessibilityControlRequestValid(
                true,
                AccessibilityProtectionProtocol.VERSION,
                10086,
                10086));
    }

    @Test
    public void acceptsHiddenSystemSenderIdentityOnOlderAndroid() {
        // Android 12/13 hide broadcast sender identity: getSendingUid() returns
        // SYSTEM_UID even though the signature-level receiver permission already
        // guarantees the sender is the module.
        assertTrue(AccessibilityServiceMerge.isAccessibilityControlRequestValid(
                true,
                AccessibilityProtectionProtocol.VERSION,
                1000,
                10086));
    }

    @Test
    public void rejectsForeignUid() {
        assertFalse(AccessibilityServiceMerge.isAccessibilityControlRequestValid(
                true,
                AccessibilityProtectionProtocol.VERSION,
                12345,
                10086));
    }

    @Test
    public void rejectsProtocolVersionMismatch() {
        assertFalse(AccessibilityServiceMerge.isAccessibilityControlRequestValid(
                true,
                AccessibilityProtectionProtocol.VERSION + 1,
                10086,
                10086));
    }

    @Test
    public void rejectsUnorderedBroadcast() {
        assertFalse(AccessibilityServiceMerge.isAccessibilityControlRequestValid(
                false,
                AccessibilityProtectionProtocol.VERSION,
                10086,
                10086));
    }

    @Test
    public void rejectsNegativeUid() {
        assertFalse(AccessibilityServiceMerge.isAccessibilityControlRequestValid(
                true,
                AccessibilityProtectionProtocol.VERSION,
                -1,
                10086));
    }
}