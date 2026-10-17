package dev.eclipseacp.client.acp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.eclipseacp.client.agent.AgentCapabilities;
import dev.eclipseacp.client.agent.AgentCommand;
import dev.eclipseacp.client.agent.AuthMethod;
import dev.eclipseacp.client.agent.ConfigOption;
import dev.eclipseacp.client.agent.ConfigValue;
import dev.eclipseacp.client.agent.SessionInfo;
import dev.eclipseacp.client.agent.SessionPage;
import dev.eclipseacp.client.agent.Usage;
import dev.eclipseacp.client.agent.PermissionOption;

/** Stateless ACP message conversion, independent of transport, lifecycle and Eclipse. */
final class AcpMessageCodec {
    private static final Pattern INLINE_IMAGE_DATA_URL = Pattern.compile(
            "data:image/[A-Za-z0-9.+-]+;base64,[A-Za-z0-9+/=_-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESTORED_INLINE_IMAGE_LINK = Pattern.compile(
            "\\[@image\\]\\((data:image/[A-Za-z0-9.+-]+;base64,[A-Za-z0-9+/=_-]+)\\)",
            Pattern.CASE_INSENSITIVE);

    private AcpMessageCodec() { }

    static JsonObject elicitationResult(String answer) {
        JsonObject result = new JsonObject();
        if (answer == null) result.addProperty("action", "cancel");
        else { result.addProperty("action", "accept"); result.addProperty("content", answer); }
        return result;
    }

    /** Prepared bytes only; the path is used solely as the URI of an embedded resource. */
    record BinaryContent(java.nio.file.Path path, String mimeType, byte[] data) { }

    static JsonObject promptParameters(String sessionId, String text, List<BinaryContent> binaries) {
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", text);
        JsonArray prompt = new JsonArray();
        prompt.add(content);
        for (BinaryContent attachment : binaries) {
            JsonObject binary = new JsonObject();
            if (attachment.mimeType().startsWith("image/") || attachment.mimeType().startsWith("audio/")) {
                binary.addProperty("type", attachment.mimeType().startsWith("audio/") ? "audio" : "image");
                binary.addProperty("mimeType", attachment.mimeType());
                binary.addProperty("data", java.util.Base64.getEncoder().encodeToString(attachment.data()));
            } else {
                binary.addProperty("type", "resource");
                JsonObject resource = new JsonObject();
                resource.addProperty("uri", attachment.path().toUri().toString());
                resource.addProperty("mimeType", attachment.mimeType());
                resource.addProperty("blob", java.util.Base64.getEncoder().encodeToString(attachment.data()));
                binary.add("resource", resource);
            }
            prompt.add(binary);
        }
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        params.add("prompt", prompt);
        return params;
    }

    static List<PermissionOption> permissionOptions(JsonObject params) {
        List<PermissionOption> options = new ArrayList<>();
        JsonArray rawOptions = params.has("options") && params.get("options").isJsonArray()
                ? params.getAsJsonArray("options")
                : new JsonArray();
        for (JsonElement element : rawOptions) {
            JsonObject option = object(element);
            options.add(new PermissionOption(
                    string(option, "optionId"),
                    string(option, "name"),
                    string(option, "kind")));
        }

        return List.copyOf(options);
    }

    static JsonObject configOptionParameters(String sessionId, String configId, ConfigValue configValue) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        params.addProperty("configId", configId);
        if (configValue != null && configValue.type() != null && !configValue.type().isBlank()) {
            params.addProperty("type", configValue.type());
        }
        params.add("value", configValue == null ? com.google.gson.JsonNull.INSTANCE
                : new com.google.gson.Gson().toJsonTree(configValue.value()));
        return params;
    }

    static JsonObject initializeParameters(int protocolVersion) {
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "Eclipse ACP");
        clientInfo.addProperty("version", "0.1.0");

        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", protocolVersion);
        JsonObject clientCapabilities = new JsonObject();
        JsonObject configOptions = new JsonObject();
        configOptions.add("boolean", new JsonObject());
        JsonObject session = new JsonObject();
        session.add("configOptions", configOptions);
        clientCapabilities.add("session", session);
        params.add("clientCapabilities", clientCapabilities);
        params.add("clientInfo", clientInfo);

        return params;
    }

    static AgentCapabilities capabilities(JsonObject result) {
        JsonObject caps = result.has("agentCapabilities") && result.get("agentCapabilities").isJsonObject()
                ? result.getAsJsonObject("agentCapabilities") : new JsonObject();
        JsonObject session = object(caps.get("sessionCapabilities"));
        JsonObject prompt = object(caps.get("promptCapabilities"));
        JsonObject mcp = object(caps.get("mcpCapabilities"));
        JsonObject auth = object(caps.get("auth"));
        return new AgentCapabilities(
                bool(caps, "loadSession"),
                present(session, "list"),
                present(session, "resume"),
                present(session, "close"),
                present(session, "delete"),
                present(session, "additionalDirectories"),
                bool(prompt, "image"),
                bool(prompt, "audio"),
                bool(prompt, "embeddedContext"),
                bool(mcp, "http"),
                bool(mcp, "sse"),
                present(auth, "logout"));
    }

    static List<AuthMethod> authenticationMethods(JsonObject result) {
        if (!result.has("authMethods") || !result.get("authMethods").isJsonArray()) return List.of();
        List<AuthMethod> methods = new ArrayList<>();
        for (JsonElement element : result.getAsJsonArray("authMethods")) {
            JsonObject raw = object(element);
            String id = string(raw, "id");
            if (id.isBlank()) continue;
            List<String> args = new ArrayList<>();
            if (raw.has("args") && raw.get("args").isJsonArray()) {
                for (JsonElement argument : raw.getAsJsonArray("args")) if (argument.isJsonPrimitive()) args.add(argument.getAsString());
            }
            Map<String, String> environment = new LinkedHashMap<>();
            JsonObject rawEnvironment = object(raw.get("env"));
            for (String name : rawEnvironment.keySet()) {
                if (rawEnvironment.get(name).isJsonPrimitive()) environment.put(name, rawEnvironment.get(name).getAsString());
            }
            String type = string(raw, "type");
            methods.add(new AuthMethod(id, string(raw, "name"), string(raw, "description"),
                    type.isBlank() ? "agent" : type, List.copyOf(args), Map.copyOf(environment)));
        }
        return List.copyOf(methods);
    }

    static JsonObject cancelledPermissionResult() {
        return permissionResult(null);
    }

    static JsonObject permissionResult(String optionId) {
        JsonObject outcome = new JsonObject();
        if (optionId == null) {
            outcome.addProperty("outcome", "cancelled");
        } else {
            outcome.addProperty("outcome", "selected");
            outcome.addProperty("optionId", optionId);
        }
        JsonObject result = new JsonObject();
        result.add("outcome", outcome);
        return result;
    }

    static JsonObject cancelledElicitationResult() {
        JsonObject result = new JsonObject();
        result.addProperty("action", "cancel");
        return result;
    }

    static String textFrom(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive value = element.getAsJsonPrimitive();
            return value.isString() ? value.getAsString() : "";
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            // ACP content is a tagged union.  In particular, some agents serialize
            // tool calls/results as chunks containing a textual preview.  Treating
            // every object with a `text` member as an agent message leaks those
            // previews into the transcript and bypasses the hide-agent-commands
            // preference.  Only an actual text content block belongs in the chat.
            if ("text".equals(string(object, "type"))
                    && object.has("text") && object.get("text").isJsonPrimitive()) {
                return object.get("text").getAsString();
            }
            // Older ACP implementations sometimes omit the tag for an otherwise
            // normal text block. Keep that compatibility, but never unwrap a
            // declared non-text block (such as tool_call or tool_result).
            if (!object.has("type") && object.has("text") && object.get("text").isJsonPrimitive()) {
                return object.get("text").getAsString();
            }
            return textFrom(object.get("content"));
        }
        StringBuilder text = new StringBuilder();
        for (JsonElement child : element.getAsJsonArray()) {
            text.append(textFrom(child));
        }
        return text.toString();
    }

    /**
     * Converts ACP chat content to Markdown suitable for the transcript. Unlike
     * {@link #textFrom(JsonElement)}, this retains inline image blocks: a loaded
     * session replays the original user prompt, including its attachments.
     */
    static String transcriptMarkdownFrom(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonPrimitive()) {
            JsonPrimitive value = element.getAsJsonPrimitive();
            return value.isString() ? value.getAsString() : "";
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            String type = string(object, "type");
            if ("image".equals(type)) return imageMarkdown(object);
            if ("text".equals(type) || !object.has("type")) {
                if (object.has("text") && object.get("text").isJsonPrimitive()) {
                    return transcriptText(object.get("text").getAsString());
                }
            }
            // Keep the same compatibility for wrapper objects as textFrom, but
            // never turn declared non-message content (for example tool results)
            // into transcript text.
            return object.has("type") && !"image".equals(type) && !"text".equals(type)
                    ? "" : transcriptMarkdownFrom(object.get("content"));
        }
        StringBuilder markdown = new StringBuilder();
        boolean previousWasImage = false;
        for (JsonElement child : element.getAsJsonArray()) {
            String part = transcriptMarkdownFrom(child);
            if (part.isEmpty()) continue;
            boolean image = isImageContent(child);
            if ((image && markdown.length() > 0) || previousWasImage) appendParagraphBreak(markdown);
            markdown.append(part);
            previousWasImage = image;
        }
        return markdown.toString();
    }

    private static boolean isImageContent(JsonElement element) {
        return element != null && element.isJsonObject()
                && "image".equals(string(element.getAsJsonObject(), "type"));
    }

    private static void appendParagraphBreak(StringBuilder markdown) {
        if (!markdown.toString().endsWith("\n\n")) markdown.append("\n\n");
    }

    private static String transcriptText(String text) {
        if (text == null || text.isEmpty()) return "";
        // codex-acp reconstructs persisted input_image items as a text link. It
        // deliberately uses [@image](...), so a generic Markdown client displays
        // an @image link. The data URL is the original image and can be rendered
        // by this transcript as the equivalent image node.
        return RESTORED_INLINE_IMAGE_LINK.matcher(text).replaceAll("\n\n![Image]($1)\n\n");
    }

    private static String imageMarkdown(JsonObject image) {
        String mimeType = string(image, "mimeType");
        String data = string(image, "data");
        String source = imageSource(mimeType, data);
        if (source.isBlank()) source = imageSource(mimeType, string(image, "uri"));
        return source.isBlank() ? "" : "![Image](" + source + ")";
    }

    private static String imageSource(String mimeType, String value) {
        if (value == null || value.isBlank()) return "";
        // Some agents replay the original data URL rather than raw Base64.
        // Prefixing it a second time leaves only the "Image" alt text visible.
        if (value.regionMatches(true, 0, "data:", 0, 5)) {
            return INLINE_IMAGE_DATA_URL.matcher(value).matches() ? value : "";
        }
        if (!mimeType.startsWith("image/")) return "";
        return "data:" + mimeType + ";base64," + value;
    }

    static JsonObject object(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    static List<AgentCommand> commands(JsonObject update) {
        JsonElement entries = update.has("availableCommands") ? update.get("availableCommands") : update.get("commands");
        if (entries == null || !entries.isJsonArray()) return List.of();
        List<AgentCommand> commands = new ArrayList<>();
        for (JsonElement entry : entries.getAsJsonArray()) {
            JsonObject command = object(entry);
            String name = nonBlank(string(command, "name"), string(command, "command"));
            if (!name.isBlank()) commands.add(new AgentCommand(name, string(command, "description")));
        }
        return List.copyOf(commands);
    }

    static List<ConfigOption> configOptions(JsonArray options) {
        List<ConfigOption> converted = new ArrayList<>();
        for (JsonElement option : options) converted.addAll(configOptions(object(option)));
        return List.copyOf(converted);
    }

    static List<ConfigOption> configOptions(JsonObject option) {
        String id = nonBlank(string(option, "configId"), string(option, "id"));
        if (id.isBlank()) return List.of();
        JsonElement value = option.has("currentValue") ? option.get("currentValue") : option.get("value");
        List<ConfigOption.Choice> choices = new ArrayList<>();
        if (option.has("options") && option.get("options").isJsonArray()) for (JsonElement raw : option.getAsJsonArray("options")) {
            if (raw.isJsonPrimitive()) {
                String choice = raw.getAsString(); choices.add(new ConfigOption.Choice(choice, choice, ""));
            } else {
                JsonObject choice = object(raw);
                String choiceValue = string(choice, "value");
                if (!choiceValue.isBlank()) choices.add(new ConfigOption.Choice(choiceValue,
                        nonBlank(string(choice, "label"), nonBlank(string(choice, "name"), choiceValue)), string(choice, "description")));
            }
        }
        return List.of(new ConfigOption(id, nonBlank(string(option, "name"), id), string(option, "description"),
                string(option, "category"), new ConfigValue(null, javaValue(value)), List.copyOf(choices)));
    }

    static Usage usage(JsonObject update) {
        return new Usage(longValue(update, "inputTokens"), longValue(update, "outputTokens"),
                longValue(update, "totalTokens"), string(update, "cost"),
                longValue(update, "used"), longValue(update, "size"));
    }

    private static Long longValue(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive() && object.get(name).getAsJsonPrimitive().isNumber()
                ? object.get(name).getAsLong() : null;
    }

    private static Object javaValue(JsonElement value) {
        return value == null || value.isJsonNull() ? null : new com.google.gson.Gson().fromJson(value, Object.class);
    }

    static String nonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    static String string(JsonObject object, String member) {
        return object.has(member) && object.get(member).isJsonPrimitive()
                ? object.get(member).getAsString()
                : "";
    }

    static SessionPage sessionPage(JsonObject result) {
        return new SessionPage(sessionInfos(result.get("sessions")), string(result, "nextCursor"));
    }

    private static List<SessionInfo> sessionInfos(JsonElement element) {
        if (element == null || !element.isJsonArray()) return List.of();
        List<SessionInfo> sessions = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            JsonObject raw = object(item);
            String id = string(raw, "sessionId");
            String cwd = string(raw, "cwd");
            if (id.isBlank() || cwd.isBlank()) continue;
            List<String> directories = new ArrayList<>();
            if (raw.has("additionalDirectories") && raw.get("additionalDirectories").isJsonArray()) {
                for (JsonElement directory : raw.getAsJsonArray("additionalDirectories")) {
                    if (directory.isJsonPrimitive()) directories.add(directory.getAsString());
                }
            }
            sessions.add(new SessionInfo(id, cwd, List.copyOf(directories), string(raw, "title"), string(raw, "updatedAt")));
        }
        return List.copyOf(sessions);
    }

    private static boolean bool(JsonObject object, String member) {
        return object.has(member) && object.get(member).isJsonPrimitive() && object.get(member).getAsBoolean();
    }

    private static boolean present(JsonObject object, String member) {
        return object.has(member) && !object.get(member).isJsonNull();
    }
}
