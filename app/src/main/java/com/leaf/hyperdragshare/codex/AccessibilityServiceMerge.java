package com.leaf.hyperdragshare.codex;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure helpers for the accessibility-service merge logic. Shared by the module
 * app and the system_server enforcer; must not depend on app runtime state.
 */
final class AccessibilityServiceMerge {
    private AccessibilityServiceMerge() {}

    /** Returns the merged value, or null when the service is already present. */
    static String appendAccessibilityServiceIfMissing(
            String currentValue,
            String componentName) {
        List<String> entries = accessibilityServiceEntries(currentValue);
        String targetIdentity = flattenedComponentIdentity(componentName);
        for (String entry : entries) {
            if (componentName.equals(entry)
                    || (targetIdentity != null
                    && targetIdentity.equals(flattenedComponentIdentity(entry)))) {
                return null;
            }
        }
        StringBuilder merged = new StringBuilder();
        boolean first = true;
        for (String entry : entries) {
            if (!first) {
                merged.append(':');
            }
            first = false;
            merged.append(entry);
        }
        if (!first) {
            merged.append(':');
        }
        merged.append(componentName);
        return merged.toString();
    }

    /** Returns the value with the service removed, or null when it is absent. */
    static String removeAccessibilityServiceIfPresent(
            String currentValue,
            String componentName) {
        List<String> entries = accessibilityServiceEntries(currentValue);
        String targetIdentity = flattenedComponentIdentity(componentName);
        boolean removed = false;
        StringBuilder kept = new StringBuilder();
        boolean first = true;
        for (String entry : entries) {
            boolean matches = componentName.equals(entry)
                    || (targetIdentity != null
                    && targetIdentity.equals(flattenedComponentIdentity(entry)));
            if (matches) {
                removed = true;
                continue;
            }
            if (!first) {
                kept.append(':');
            }
            first = false;
            kept.append(entry);
        }
        return removed ? kept.toString() : null;
    }

    static boolean containsAccessibilityService(
            String currentValue,
            String componentName) {
        String targetIdentity = flattenedComponentIdentity(componentName);
        for (String entry : accessibilityServiceEntries(currentValue)) {
            if (componentName.equals(entry)
                    || (targetIdentity != null
                    && targetIdentity.equals(flattenedComponentIdentity(entry)))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> accessibilityServiceEntries(String value) {
        ArrayList<String> entries = new ArrayList<>();
        if (value == null || value.isEmpty()) {
            return entries;
        }
        for (String entry : value.split(":")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                entries.add(trimmed);
            }
        }
        return entries;
    }

    /**
     * Normalizes a flattened component to packageName/className where a leading
     * dot is expanded against the package name. Returns null when malformed.
     */
    static String flattenedComponentIdentity(String value) {
        if (value == null) {
            return null;
        }
        int separator = value.indexOf('/');
        if (separator <= 0 || separator == value.length() - 1) {
            return null;
        }
        String packageName = value.substring(0, separator);
        String rawClassName = value.substring(separator + 1);
        String className = rawClassName.startsWith(".")
                ? packageName + rawClassName
                : rawClassName;
        return packageName.trim().isEmpty() || className.trim().isEmpty()
                ? null
                : packageName.trim() + "/" + className.trim();
    }

    static String signerIdentity(List<String> digests) {
        if (digests == null || digests.isEmpty()) {
            return null;
        }
        ArrayList<String> normalized = new ArrayList<>();
        for (String digest : digests) {
            if (digest == null) {
                continue;
            }
            String trimmed = digest.trim().toLowerCase(Locale.ROOT);
            if (trimmed.isEmpty() || normalized.contains(trimmed)) {
                continue;
            }
            normalized.add(trimmed);
        }
        normalized.sort(String::compareTo);
        if (normalized.isEmpty()) {
            return null;
        }
        StringBuilder identity = new StringBuilder();
        boolean first = true;
        for (String digest : normalized) {
            if (!first) {
                identity.append(',');
            }
            first = false;
            identity.append(digest);
        }
        return identity.toString();
    }

    static boolean isPinnedSignerAccepted(
            String pinnedSigner,
            List<String> currentDigests,
            List<String> historyDigests,
            boolean hasMultipleSigners) {
        String pinned = pinnedSigner == null ? "" : pinnedSigner.trim().toLowerCase(Locale.ROOT);
        if (pinned.isEmpty()) {
            return false;
        }
        if (hasMultipleSigners) {
            return pinned.equals(signerIdentity(currentDigests));
        }
        for (String digest : historyDigests) {
            if (digest != null && pinned.equals(digest.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static boolean isAccessibilityControlRequestValid(
            boolean ordered,
            int protocolVersion,
            int senderUid,
            int appUid) {
        return ordered
                && protocolVersion == AccessibilityProtectionProtocol.VERSION
                && senderUid >= 0
                && senderUid == appUid;
    }
}