package com.leaf.hyperdragshare.codex;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MainHook implements IXposedHookLoadPackage {
    /** LSPosed matches the system_server process by the "android" package name. */
    static final String SYSTEM_SERVER_PACKAGE = "android";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (SYSTEM_SERVER_PACKAGE.equals(lpparam.packageName)) {
            AccessibilityProtectionHooks.install(lpparam.classLoader);
        }
    }
}