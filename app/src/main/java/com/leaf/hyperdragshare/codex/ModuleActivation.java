package com.leaf.hyperdragshare.codex;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Root availability checks and root Shell helpers for the settings UI. */
final class ModuleActivation {
    private static final long ROOT_TIMEOUT_SECONDS = 4L;

    private ModuleActivation() {}

    static boolean hasRootAccess() {
        return runRootCommand("id -u");
    }

    private static boolean runRootCommand(String command) {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException ignored) {
            if (ignored instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        } finally {
            if (process != null) {
                try {
                    process.getInputStream().close();
                } catch (IOException ignored) {
                    // Process cleanup only.
                }
                try {
                    process.getOutputStream().close();
                } catch (IOException ignored) {
                    // Process cleanup only.
                }
                process.destroy();
            }
        }
    }
}