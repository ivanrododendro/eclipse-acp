package dev.eclipseacp.client.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Persisted MCP declaration. Empty providerId/projectName means "all". */
public record McpServerConfig(String name, String transport, String command, List<String> args,
        Map<String, String> environment, String url, Map<String, String> headers,
        String providerId, String projectName, boolean enabled) {
    public McpServerConfig {
        name = name == null ? "" : name.trim(); transport = transport == null || transport.isBlank() ? "stdio" : transport.trim();
        command = command == null ? "" : command.trim(); args = args == null ? List.of() : List.copyOf(args);
        environment = environment == null ? Map.of() : Map.copyOf(environment); url = url == null ? "" : url.trim();
        headers = headers == null ? Map.of() : Map.copyOf(headers); providerId = providerId == null ? "" : providerId.trim();
        projectName = projectName == null ? "" : projectName.trim();
    }
    public boolean appliesTo(String provider, String project) {
        return enabled && (providerId.isBlank() || providerId.equals(provider)) && (projectName.isBlank() || projectName.equals(project));
    }
    public JsonObject toAcp(boolean http, boolean sse) {
        if (name.isBlank()) throw new IllegalArgumentException("An MCP server name is required");
        JsonObject server = new JsonObject(); server.addProperty("name", name);
        if ("stdio".equals(transport)) {
            if (command.isBlank() || !Path.of(command).isAbsolute()) throw new IllegalArgumentException("MCP stdio command for " + name + " must be an absolute path");
            server.addProperty("command", command); JsonArray array = new JsonArray(); args.forEach(array::add); server.add("args", array); server.add("env", pairs(resolve(environment)));
        } else if ("http".equals(transport) || "sse".equals(transport)) {
            if (("http".equals(transport) && !http) || ("sse".equals(transport) && !sse)) throw new IllegalArgumentException("Agent does not support MCP " + transport.toUpperCase());
            if (url.isBlank()) throw new IllegalArgumentException("MCP " + transport + " URL for " + name + " is required");
            server.addProperty("type", transport); server.addProperty("url", url); server.add("headers", pairs(resolve(headers)));
        } else throw new IllegalArgumentException("Unsupported MCP transport: " + transport);
        return server;
    }
    private static JsonArray pairs(Map<String, String> values) { JsonArray result = new JsonArray(); values.forEach((name, value) -> { JsonObject pair = new JsonObject(); pair.addProperty("name", name); pair.addProperty("value", value); result.add(pair); }); return result; }
    /** Resolve ${env:NAME} only at connection time, so tokens need not be stored in Eclipse preferences. */
    private static Map<String, String> resolve(Map<String, String> values) {
        java.util.LinkedHashMap<String, String> resolved = new java.util.LinkedHashMap<>();
        values.forEach((name, value) -> {
            if (value != null && value.startsWith("${env:") && value.endsWith("}")) {
                String environmentName = value.substring(6, value.length() - 1);
                String environmentValue = System.getenv(environmentName);
                if (environmentValue == null) throw new IllegalArgumentException("Missing environment variable for MCP " + name + ": " + environmentName);
                resolved.put(name, environmentValue);
            } else resolved.put(name, value == null ? "" : value);
        });
        return resolved;
    }
}
