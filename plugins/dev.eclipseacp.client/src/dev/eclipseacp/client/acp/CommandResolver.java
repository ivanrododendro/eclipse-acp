package dev.eclipseacp.client.acp;

import java.io.IOException;

/** Resolves agent commands using the native execution rules of the host operating system. */
interface CommandResolver {
    String resolve(String command) throws IOException;
}
