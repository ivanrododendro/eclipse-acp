package dev.eclipseacp.client.acp;

import java.io.Reader;
import java.io.Writer;
import java.util.function.Consumer;

import dev.eclipseacp.client.preferences.AcpPreferences;

/** Factory for the standard line-delimited JSON-RPC connection. */
final class DefaultJsonRpcTransportFactory implements JsonRpcTransportFactory {
    @Override
    public JsonRpcTransport create(Reader reader, Writer writer, JsonRpcHandler handler, Consumer<Throwable> errorHandler) {
        return new JsonRpcConnection(reader, writer, handler, errorHandler, JsonRpcConnection::requestTimeoutMillis,
                DiagnosticSink.eclipse(), AcpPreferences.maxJsonRpcFrameCharacters(AcpPreferences.store()));
    }
}
