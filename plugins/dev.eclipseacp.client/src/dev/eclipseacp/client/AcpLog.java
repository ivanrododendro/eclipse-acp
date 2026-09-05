package dev.eclipseacp.client;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/** Centralized logging for the ACP client. */
public final class AcpLog {
    private static final Bundle BUNDLE = FrameworkUtil.getBundle(AcpLog.class);
    private static final ILog LOG = BUNDLE == null ? null : Platform.getLog(BUNDLE);

    private AcpLog() {
    }

    public static void info(String message) {
        log(IStatus.INFO, message, null);
    }

    public static void warn(String message, Throwable error) {
        log(IStatus.WARNING, message, error);
    }

    public static void error(String message, Throwable error) {
        log(IStatus.ERROR, message, error);
    }

    private static void log(int severity, String message, Throwable error) {
        if (LOG != null) {
            LOG.log(new Status(severity, PluginIds.PLUGIN_ID, message, error));
        }
    }
}
