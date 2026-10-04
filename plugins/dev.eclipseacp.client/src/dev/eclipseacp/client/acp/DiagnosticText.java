package dev.eclipseacp.client.acp;

import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** Bounds diagnostic output and redacts well-known secret-bearing JSON fields. */
final class DiagnosticText {
    static final int MAX_JSON_CHARACTERS = 8 * 1024;
    static final int MAX_STDERR_CHARACTERS = 4 * 1024;
    private static final String REDACTED = "[REDACTED]";
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "accesstoken", "apikey", "authorization", "cookie", "credential", "password",
            "refreshtoken", "secret", "token");

    private DiagnosticText() {
    }

    static String json(JsonElement value) {
        return truncate(redact(value).toString(), MAX_JSON_CHARACTERS);
    }

    static String stderr(String value) {
        return truncate(value, MAX_STDERR_CHARACTERS);
    }

    private static JsonElement redact(JsonElement value) {
        if (value == null || value.isJsonNull()) return JsonNull.INSTANCE;
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement item : value.getAsJsonArray()) result.add(redact(item));
            return result;
        }
        if (!value.isJsonObject()) return value.deepCopy();

        JsonObject result = new JsonObject();
        value.getAsJsonObject().entrySet().forEach(entry -> result.add(entry.getKey(),
                isSensitive(entry.getKey()) ? new com.google.gson.JsonPrimitive(REDACTED) : redact(entry.getValue())));
        return result;
    }

    private static boolean isSensitive(String key) {
        String normalized = key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return SENSITIVE_KEYS.contains(normalized) || normalized.endsWith("token")
                || normalized.endsWith("secret") || normalized.endsWith("password");
    }

    private static String truncate(String value, int maximum) {
        if (value == null || value.length() <= maximum) return value;
        return value.substring(0, maximum) + "… [truncated " + (value.length() - maximum) + " characters]";
    }
}
