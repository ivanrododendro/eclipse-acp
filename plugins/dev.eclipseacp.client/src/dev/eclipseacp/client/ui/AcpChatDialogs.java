package dev.eclipseacp.client.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.ui.dialogs.ListDialog;

import dev.eclipseacp.client.agent.AuthMethod;
import dev.eclipseacp.client.agent.ConfigOption;
import dev.eclipseacp.client.agent.PromptAttachment;
import dev.eclipseacp.client.agent.ElicitationRequest;
import dev.eclipseacp.client.agent.PermissionOption;
import dev.eclipseacp.client.agent.PermissionRequest;
import dev.eclipseacp.client.agent.ToolCall;

/** Owns modal interactions for the chat and its agent. */
final class AcpChatDialogs {
    private final Supplier<Shell> shell;
    private final Consumer<Runnable> ui;

    AcpChatDialogs(Supplier<Shell> shell, Consumer<Runnable> ui) {
        this.shell = shell;
        this.ui = ui;
    }

    String chooseCommand(Map<String, String> commands) {
        if (commands.isEmpty()) return null;
        ListDialog dialog = new ListDialog(shell.get());
        dialog.setTitle("ACP commands");
        dialog.setMessage("Insert a slash command:");
        dialog.setContentProvider(ArrayContentProvider.getInstance());
        dialog.setLabelProvider(new LabelProvider() {
            @Override public String getText(Object value) {
                String name = (String) value;
                String description = commands.get(name);
                return "/" + name + (description.isBlank() ? "" : " — " + description);
            }
        });
        dialog.setInput(commands.keySet());
        return dialog.open() == Window.OK && dialog.getResult() != null && dialog.getResult().length > 0
                ? "/" + dialog.getResult()[0] + " " : null;
    }

    Map<ConfigOption, Object> editConfigOptions(List<ConfigOption> options) {
        ConfigOptionsDialog dialog = new ConfigOptionsDialog(shell.get(), options);
        return dialog.open() == Window.OK ? dialog.changedValues() : Map.of();
    }

    PromptAttachment chooseAttachment() throws IOException {
        FileDialog dialog = new FileDialog(shell.get(), SWT.OPEN);
        dialog.setText("Attach image or audio");
        dialog.setFilterExtensions(new String[] { "*.png;*.jpg;*.jpeg;*.gif;*.webp;*.mp3;*.wav;*.ogg", "*.*" });
        String selected = dialog.open();
        if (selected == null) return null;
        Path path = Path.of(selected);
        if (Files.size(path) > 10 * 1024 * 1024) {
            MessageDialog.openWarning(shell.get(), "Attachment too large", "Attachments are limited to 10 MiB.");
            return null;
        }
        String mime = Files.probeContentType(path);
        if (mime == null || !(mime.startsWith("image/") || mime.startsWith("audio/"))) {
            MessageDialog.openWarning(shell.get(), "Unsupported attachment", "Choose an image or audio file.");
            return null;
        }
        return new PromptAttachment(path, mime);
    }

    CompletableFuture<String> requestElicitation(String sessionLabel, ElicitationRequest request) {
        CompletableFuture<String> result = new CompletableFuture<>();
        ui.accept(() -> {
            String title = nonBlank(request.message(), nonBlank(request.title(), "Agent input required"));
            InputDialog dialog = new InputDialog(shell.get(), "ACP input — " + sessionLabel, title, "", null);
            result.complete(dialog.open() == Window.OK ? dialog.getValue() : null);
        });
        return result;
    }

    CompletableFuture<String> requestAuthentication(String sessionLabel, List<AuthMethod> methods) {
        CompletableFuture<String> result = new CompletableFuture<>();
        ui.accept(() -> {
            List<AuthMethod> available = methods.stream().filter(method -> !method.isTerminal()).toList();
            if (available.isEmpty() || shell.get().isDisposed()) {
                result.complete(null);
                return;
            }
            String[] labels = available.stream().map(AuthMethod::name).toArray(String[]::new);
            MessageDialog dialog = new MessageDialog(shell.get(), "ACP sign in — " + sessionLabel, null,
                    "This agent requires authentication before it can open a session. Choose a sign-in method.",
                    MessageDialog.QUESTION, labels, 0);
            int selected = dialog.open();
            result.complete(selected >= 0 && selected < available.size() ? available.get(selected).id() : null);
        });
        return result;
    }

    CompletableFuture<String> requestPermission(String sessionLabel, String title, List<PermissionOption> options) {
        return requestPermission(sessionLabel, new PermissionRequest(title, null, options));
    }

    CompletableFuture<String> requestPermission(String sessionLabel, PermissionRequest request) {
        CompletableFuture<String> result = new CompletableFuture<>();
        ui.accept(() -> {
            List<PermissionOption> options = request.options();
            if (options.isEmpty() || shell.get().isDisposed()) {
                result.complete(null);
                return;
            }
            String[] labels = options.stream().map(PermissionOption::name).toArray(String[]::new);
            MessageDialog dialog = new MessageDialog(shell.get(), "ACP permission — " + sessionLabel, null,
                    permissionDetail(request),
                    MessageDialog.QUESTION, labels, defaultPermissionIndex(options));
            int selected = dialog.open();
            result.complete(selected >= 0 && selected < options.size() ? options.get(selected).id() : null);
        });
        return result;
    }

    static int defaultPermissionIndex(List<PermissionOption> options) {
        for (int index = 0; index < options.size(); index++) {
            if (options.get(index).kind().startsWith("reject")) return index;
        }
        return options.size() - 1;
    }

    static String permissionDetail(PermissionRequest request) {
        ToolCall tool = request.toolCall();
        if (tool == null) return request.title();
        StringBuilder detail = new StringBuilder(request.title()).append("\n\nTool: ").append(tool.kind());
        if (!tool.locations().isEmpty()) {
            detail.append("\nFiles:");
            tool.locations().forEach(location -> detail.append("\n• ").append(location.path())
                    .append(location.line() == null ? "" : ":" + location.line()));
        }
        if (!tool.diffs().isEmpty()) detail.append("\nChanges proposed: ").append(tool.diffs().size());
        appendField(detail, "Command", tool.command());
        appendField(detail, "Working directory", tool.workingDirectory());
        appendField(detail, "Path", tool.path());
        return detail.toString();
    }

    private static void appendField(StringBuilder detail, String label, String value) {
        if (value != null && !value.isBlank()) detail.append('\n').append(label).append(": ").append(value);
    }

    private static String nonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }
}
