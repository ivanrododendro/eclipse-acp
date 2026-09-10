package dev.eclipseacp.client.acp;

import java.io.Reader;
import java.io.Writer;
import java.util.function.Consumer;

/** Creates JSON-RPC transports independently from ACP message adaptation. */
interface JsonRpcTransportFactory {
    JsonRpcTransport create(Reader reader, Writer writer, JsonRpcHandler handler, Consumer<Throwable> errorHandler);
}
