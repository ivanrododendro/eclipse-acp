package dev.eclipseacp.client.acp;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Child-process fixture: base64 keeps empty values, quotes and Unicode unambiguous. */
public final class ArgumentEcho {
    public static void main(String[] arguments) {
        for (String argument : arguments) {
            System.out.println("ARG:" + Base64.getEncoder().encodeToString(argument.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
