package dev.eclipseacp.client.acp;

import java.io.Closeable;
import java.io.Reader;
import java.io.Writer;

/** Running agent process, exposing only the streams needed by the ACP transport. */
interface AgentProcess extends Closeable {
    Reader standardOutput();

    Writer standardInput();
}
