package dev.eclipseacp.client.acp;

import java.util.function.Supplier;

import dev.eclipseacp.client.AcpLog;

/** Diagnostic boundary used by the protocol transport, including in headless tests. */
interface DiagnosticSink {
    void info(String message);

    void trace(Supplier<String> message);

    void warn(String message, Throwable error);

    void error(String message, Throwable error);

    static DiagnosticSink eclipse() {
        return new DiagnosticSink() {
            @Override public void info(String message) { AcpLog.info(message); }
            @Override public void trace(Supplier<String> message) { AcpLog.debug(message); }
            @Override public void warn(String message, Throwable error) { AcpLog.warn(message, error); }
            @Override public void error(String message, Throwable error) { AcpLog.error(message, error); }
        };
    }
}
