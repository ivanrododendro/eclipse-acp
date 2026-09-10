package dev.eclipseacp.client;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import dev.eclipseacp.client.preferences.AcpPreferences;

/** Centralized logging for the ACP client. */
public final class AcpLog {
    private static final Bundle BUNDLE = FrameworkUtil.getBundle(AcpLog.class);
    private static final ILog LOG = BUNDLE == null ? null : Platform.getLog(BUNDLE);

    private AcpLog() {
    }

    public static void info(String message) {
        log(IStatus.INFO, message, null);
    }

    /**
     * Emits diagnostics enabled from the ACP preference page. Eclipse IStatus has no DEBUG
     * severity, so DEBUG is explicitly included in the message while using INFO severity.
     */
    public static void debug(String message) {
        if (AcpPreferences.store().getBoolean(AcpPreferences.DEBUG_ACP_MESSAGES)) {
            log(IStatus.INFO, "DEBUG " + message, null);
        }
    }

    public static void warn(String message, Throwable error) {
        log(IStatus.WARNING, message, error);
    }

    public static void error(String message, Throwable error) {
        log(IStatus.ERROR, message, error);
    }

    private static void log(int severity, String message, Throwable error) {
        if (LOG != null) {
            LOG.log(status(severity, message, error));
        }
    }

    private static IStatus status(int severity, String message, Throwable error) {
        return new Status(severity, PluginIds.PLUGIN_ID, message, error);
    }
}
