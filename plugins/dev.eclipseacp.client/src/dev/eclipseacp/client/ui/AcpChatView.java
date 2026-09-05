package dev.eclipseacp.client.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

import dev.eclipseacp.client.acp.AcpListener;
import dev.eclipseacp.client.acp.FileDiff;
import dev.eclipseacp.client.acp.FileReadRequest;
import dev.eclipseacp.client.acp.FileWriteRequest;
import dev.eclipseacp.client.acp.PermissionRequest;
import dev.eclipseacp.client.acp.PermissionOption;
import dev.eclipseacp.client.acp.ToolCall;
import dev.eclipseacp.client.agent.AgentClient;
import dev.eclipseacp.client.agent.AgentClientFactory;
import dev.eclipseacp.client.agent.AgentProvider;
import dev.eclipseacp.client.preferences.AgentProviderRegistry;
import dev.eclipseacp.client.preferences.AcpPreferences;

public final class AcpChatView extends ViewPart implements AcpListener {
    public static final String ID = "dev.eclipseacp.client.views.chat";
    private Browser transcript;
    private Text prompt;
    private Button sendButton;
    private Button stopButton;
    private Button closeButton;
    private Button applyButton;
    private Button rejectButton;
    private Button undoButton;
    private Label status;
    private Combo projectSelector;
    private final List<ChatSession> sessions = new ArrayList<>();
    private ChatSession activeSession;

    private static final class ChatSession {
        private final IProject project;
        private final String label;
        private final boolean reviewFileChanges;
        private final StringBuilder transcriptMarkdown = new StringBuilder();
        private AgentClient client;
        private boolean agentMessageOpen;
        private final Map<String, ToolCall> toolCalls = new LinkedHashMap<>();
        private final Map<String, FileDiff> pendingChanges = new LinkedHashMap<>();
        private final WorkspaceDiffApplier diffApplier = new WorkspaceDiffApplier();

        private ChatSession(IProject project, String label, boolean reviewFileChanges) {
            this.project = project;
            this.label = label;
            this.reviewFileChanges = reviewFileChanges;
        }
    }

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        Composite header = new Composite(parent, SWT.NONE);
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        header.setLayout(new GridLayout(2, false));
        Label projectLabel = new Label(header, SWT.NONE);
        projectLabel.setText("Project:");
        projectSelector = new Combo(header, SWT.DROP_DOWN | SWT.READ_ONLY);
        projectSelector.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
        projectSelector.addListener(SWT.Selection, ignored -> selectProjectFromCombo());

        transcript = new Browser(parent, SWT.BORDER);
        transcript.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        transcript.addProgressListener(new ProgressAdapter() {
            @Override
            public void completed(org.eclipse.swt.browser.ProgressEvent event) {
                scrollTranscriptToBottom();
            }
        });
        transcript.setText(GfmRenderer.document(""));

        prompt = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        GridData promptData = new GridData(SWT.FILL, SWT.FILL, true, false);
        promptData.heightHint = 72;
        prompt.setLayoutData(promptData);
        prompt.setMessage("Ask the ACP agent…  (Ctrl+Enter to send)");
        prompt.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if ((event.stateMask & SWT.MOD1) != 0 && (event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR)) {
                    sendPrompt();
                }
            }
        });

        Composite actions = new Composite(parent, SWT.NONE);
        actions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        actions.setLayout(new GridLayout(7, false));

        sendButton = new Button(actions, SWT.PUSH);
        sendButton.setText("Send");
        sendButton.setEnabled(false);
        sendButton.addListener(SWT.Selection, ignored -> sendPrompt());

        stopButton = new Button(actions, SWT.PUSH);
        stopButton.setText("Stop");
        stopButton.setEnabled(false);
        stopButton.addListener(SWT.Selection, ignored -> cancel());

        closeButton = new Button(actions, SWT.PUSH);
        closeButton.setText("Close");
        closeButton.setEnabled(false);
        closeButton.addListener(SWT.Selection, ignored -> closeActiveSession());

        applyButton = new Button(actions, SWT.PUSH);
        applyButton.setText("Apply changes");
        applyButton.setEnabled(false);
        applyButton.addListener(SWT.Selection, ignored -> applyChanges());

        rejectButton = new Button(actions, SWT.PUSH);
        rejectButton.setText("Reject changes");
        rejectButton.setEnabled(false);
        rejectButton.addListener(SWT.Selection, ignored -> rejectChanges());

        undoButton = new Button(actions, SWT.PUSH);
        undoButton.setText("Undo apply");
        undoButton.setEnabled(false);
        undoButton.addListener(SWT.Selection, ignored -> undoApply());

        status = new Label(actions, SWT.NONE);
        status.setText("Not connected");
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    /** Called only by the project/resource context-menu command. */
    public void openSessionFor(IProject project) {
        if (project == null || !project.exists() || !project.isOpen() || project.getLocation() == null) {
            onError("Cannot open ACP session", new IllegalArgumentException("The selected project is not open"));
            return;
        }
        AgentProvider provider = new AgentProviderRegistry(AcpPreferences.store()).active();
        String agentName = provider.name();

        boolean reviewFileChanges = AcpPreferences.store().getBoolean(AcpPreferences.REVIEW_FILE_CHANGES);
        ChatSession session = new ChatSession(project, sessionLabel(project), reviewFileChanges);
        sessions.add(session);
        projectSelector.add(session.label);
        activeSession = session;
        projectSelector.select(projectSelector.getItemCount() - 1);
        renderTranscript();
        append(session, "Connecting to " + agentName + " in " + project.getLocation() + "…"
                + (reviewFileChanges ? " Changes will be reviewed before applying." : " Changes apply immediately.") + "\n\n");
        AgentClient newClient = AgentClientFactory.create(provider, listenerFor(session), reviewFileChanges);
        session.client = newClient;
        newClient.connect(project.getLocation().toFile().toPath()).whenComplete((ignored, error) -> ui(() -> {
            if (session.client != newClient) {
                return; // A newer connection replaced this one.
            }
            if (error != null) {
                onError(session, "Could not start " + agentName, unwrap(error));
                closeSession(session);
                return;
            }
            updateControls();
            if (activeSession == session) prompt.setFocus();
        }));
    }

    private void selectProjectFromCombo() {
        int index = projectSelector.getSelectionIndex();
        if (index < 0 || index >= sessions.size()) return;
        activeSession = sessions.get(index);
        renderTranscript();
        updateControls();
    }

    private String sessionLabel(IProject project) {
        int number = 1;
        for (ChatSession session : sessions) {
            if (session.project.equals(project)) number++;
        }
        return number == 1 ? project.getName() : project.getName() + " (session " + number + ")";
    }

    private void sendPrompt() {
        String text = prompt.getText().trim();
        ChatSession session = activeSession;
        if (text.isEmpty() || session == null || session.client == null) {
            return;
        }
        prompt.setText("");
        session.agentMessageOpen = false;
        append(session, "## You\n\n" + text + "\n\n## Agent\n\n");
        session.agentMessageOpen = true;
        updateControls();

        AgentClient activeClient = session.client;
        activeClient.prompt(text).whenComplete((ignored, error) -> ui(() -> {
            if (session.client != activeClient) {
                return; // The response belongs to an earlier connection.
            }
            if (error != null) {
                onError(session, "Prompt failed", unwrap(error));
            } else if (session.agentMessageOpen) {
                append(session, "\n\n");
            }
            session.agentMessageOpen = false;
            updateControls();
        }));
    }

    private void cancel() {
        if (activeSession == null || activeSession.client == null) {
            return;
        }
        try {
            activeSession.client.cancel();
        } catch (IOException exception) {
            onError(activeSession, "Could not cancel the current prompt", exception);
        }
    }

    private AcpListener listenerFor(ChatSession session) {
        return new AcpListener() {
            @Override public void onAgentText(String text) { ui(() -> append(session, text)); }
            @Override public void onStatus(String value) { setStatus(session, value); }
            @Override public void onError(String message, Throwable error) { AcpChatView.this.onError(session, message, error); }
            @Override public CompletableFuture<String> requestPermission(String title, List<PermissionOption> options) {
                return requestPermissionFor(session, title, options);
            }
            @Override public CompletableFuture<String> requestPermission(PermissionRequest request) {
                return requestPermissionFor(session, request);
            }
            @Override public void onToolCall(ToolCall toolCall) { ui(() -> updateToolCall(session, toolCall)); }
            @Override public CompletableFuture<String> readTextFile(FileReadRequest request) {
                return CompletableFuture.supplyAsync(() -> {
                    try { return session.diffApplier.read(session.project, request.path(), request.line(), request.limit()); }
                    catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
                });
            }
            @Override public CompletableFuture<Void> stageFileWrite(FileWriteRequest request) {
                return CompletableFuture.runAsync(() -> {
                    try {
                        FileDiff diff = session.diffApplier.preview(session.project, request.path(), request.content());
                        if (!session.reviewFileChanges) {
                            session.diffApplier.apply(session.project, List.of(diff));
                            ui(() -> append(session, "> **File write applied:** `" + diff.path() + "`\n\n"));
                            return;
                        }
                        ui(() -> {
                            session.pendingChanges.put(diff.path(), diff);
                            append(session, "> **File write staged:** `" + diff.path() + "`\n\n");
                            updateControls();
                        });
                    } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
                });
            }
        };
    }

    @Override
    public void onAgentText(String text) {
        ui(() -> append(activeSession, text));
    }

    @Override
    public void onStatus(String value) {
        setStatus(activeSession, value);
    }

    @Override
    public void onError(String message, Throwable error) {
        onError(activeSession, message, error);
    }

    private void setStatus(ChatSession session, String value) {
        ui(() -> {
            if (session == activeSession && status != null && !status.isDisposed()) {
                status.setText(value);
                status.getParent().layout();
            }
        });
    }

    private void onError(ChatSession session, String message, Throwable error) {
        ui(() -> {
            if (session == null) return;
            String detail = error == null || error.getMessage() == null ? "" : ": " + error.getMessage();
            append(session, "\n> **Error:** " + message + detail + "\n\n");
            setStatus(session, "Error");
        });
    }

    @Override
    public CompletableFuture<String> requestPermission(String title, List<PermissionOption> options) {
        return requestPermissionFor(activeSession, title, options);
    }

    private CompletableFuture<String> requestPermissionFor(ChatSession session, String title, List<PermissionOption> options) {
        return requestPermissionFor(session, new PermissionRequest(title, null, options));
    }

    private CompletableFuture<String> requestPermissionFor(ChatSession session, PermissionRequest request) {
        CompletableFuture<String> result = new CompletableFuture<>();
        ui(() -> {
            List<PermissionOption> options = request.options();
            if (options.isEmpty() || getSite().getShell().isDisposed()) {
                result.complete(null);
                return;
            }
            String[] labels = options.stream().map(PermissionOption::name).toArray(String[]::new);
            int defaultIndex = defaultPermissionIndex(options);
            String detail = permissionDetail(request);
            MessageDialog dialog = new MessageDialog(
                    getSite().getShell(),
                    "ACP permission",
                    null,
                    detail,
                    MessageDialog.QUESTION,
                    labels,
                    defaultIndex);
            int selected = dialog.open();
            result.complete(selected >= 0 && selected < options.size() ? options.get(selected).id() : null);
        });
        return result;
    }

    private static String permissionDetail(PermissionRequest request) {
        ToolCall tool = request.toolCall();
        if (tool == null) return request.title();
        StringBuilder detail = new StringBuilder(request.title());
        detail.append("\n\nTool: ").append(tool.kind());
        if (!tool.locations().isEmpty()) {
            detail.append("\nFiles:");
            tool.locations().forEach(location -> detail.append("\n• ").append(location.path())
                    .append(location.line() == null ? "" : ":" + location.line()));
        }
        if (!tool.diffs().isEmpty()) detail.append("\nChanges proposed: ").append(tool.diffs().size());
        if (tool.rawInput() instanceof JsonObject input) {
            appendPermissionField(detail, "Command", input, "command");
            appendPermissionField(detail, "Working directory", input, "cwd");
            appendPermissionField(detail, "Path", input, "path");
        }
        return detail.toString();
    }

    private static void appendPermissionField(StringBuilder detail, String label, JsonObject input, String field) {
        if (input.has(field) && input.get(field).isJsonPrimitive()) {
            detail.append('\n').append(label).append(": ").append(input.get(field).getAsString());
        }
    }

    private void updateToolCall(ChatSession session, ToolCall toolCall) {
        session.toolCalls.put(toolCall.id(), toolCall);
        if (session.reviewFileChanges) toolCall.diffs().forEach(diff -> session.pendingChanges.put(diff.path(), diff));
        append(session, "\n> **Tool " + toolCall.kind() + ":** " + toolCall.title() + " — " + toolCall.status()
                + (toolCall.hasDiffs() ? " (" + toolCall.diffs().size() + " file change(s) ready for review)" : "")
                + toolDiffPreview(toolCall.diffs()) + "\n\n");
        updateControls();
        if (!session.reviewFileChanges && toolCall.hasDiffs()) applyImmediately(session, toolCall.diffs());
    }

    private void applyImmediately(ChatSession session, List<FileDiff> diffs) {
        CompletableFuture.runAsync(() -> {
            try {
                session.diffApplier.apply(session.project, diffs);
                ui(() -> setStatus(session, "Changes applied"));
            } catch (Exception error) { onError(session, "Could not apply agent changes", error); }
        });
    }

    private static String toolDiffPreview(List<FileDiff> diffs) {
        if (diffs.isEmpty()) return "";
        StringBuilder preview = new StringBuilder();
        for (FileDiff diff : diffs) {
            preview.append("\n\n```diff\n--- ").append(diff.path()).append("\n+++ ").append(diff.path()).append("\n");
            appendDiffLines(preview, '-', diff.oldText());
            appendDiffLines(preview, '+', diff.newText());
            preview.append("```");
        }
        return preview.toString();
    }

    private static void appendDiffLines(StringBuilder preview, char prefix, String text) {
        if (text == null) return;
        String[] lines = text.split("\\R", -1);
        int maximumLines = 40;
        for (int index = 0; index < Math.min(lines.length, maximumLines); index++) {
            preview.append(prefix).append(lines[index]).append('\n');
        }
        if (lines.length > maximumLines) preview.append(prefix).append("… ").append(lines.length - maximumLines).append(" more lines\n");
    }

    private List<FileDiff> pendingDiffs(ChatSession session) {
        if (session == null) return List.of();
        return List.copyOf(session.pendingChanges.values());
    }

    private void applyChanges() {
        ChatSession session = activeSession;
        if (session == null) return;
        List<FileDiff> diffs = pendingDiffs(session);
        CompletableFuture.runAsync(() -> {
            try {
                int count = session.diffApplier.apply(session.project, diffs);
                ui(() -> {
                    session.pendingChanges.clear();
                    append(session, "> Applied " + count + " reviewed file change(s).\n\n");
                    setStatus(session, "Changes applied");
                    updateControls();
                });
            } catch (Exception error) { onError(session, "Could not apply reviewed changes", error); }
        });
    }

    private void rejectChanges() {
        ChatSession session = activeSession;
        if (session == null) return;
        List<FileDiff> diffs = pendingDiffs(session);
        CompletableFuture.runAsync(() -> {
            try {
                int reverted = session.diffApplier.reject(session.project, diffs);
                ui(() -> {
                    session.pendingChanges.clear();
                    append(session, "> Rejected " + diffs.size() + " reviewed file change(s)"
                            + (reverted == 0 ? "." : " and reverted " + reverted + " direct write(s).") + "\n\n");
                    setStatus(session, "Changes rejected");
                    updateControls();
                });
            } catch (Exception error) { onError(session, "Could not reject reviewed changes", error); }
        });
    }

    private void undoApply() {
        ChatSession session = activeSession;
        if (session == null) return;
        CompletableFuture.runAsync(() -> {
            try {
                int count = session.diffApplier.undo();
                ui(() -> {
                    append(session, "> Undid " + count + " applied file change(s).\n\n");
                    setStatus(session, "Changes undone");
                    updateControls();
                });
            } catch (Exception error) { onError(session, "Could not undo applied changes", error); }
        });
    }

    private static int defaultPermissionIndex(List<PermissionOption> options) {
        for (int index = 0; index < options.size(); index++) {
            if (options.get(index).kind().startsWith("reject")) {
                return index;
            }
        }
        return options.size() - 1;
    }

    private void append(ChatSession session, String text) {
        if (session == null) {
            return;
        }
        session.transcriptMarkdown.append(text);
        if (session != activeSession || transcript == null || transcript.isDisposed()) return;
        transcript.setText(GfmRenderer.document(session.transcriptMarkdown.toString()));
        // setText starts an asynchronous page load; the progress listener above
        // repeats this after the new document has been laid out.
        scrollTranscriptToBottom();
    }

    private void renderTranscript() {
        if (transcript == null || transcript.isDisposed() || activeSession == null) return;
        transcript.setText(GfmRenderer.document(activeSession.transcriptMarkdown.toString()));
        scrollTranscriptToBottom();
    }

    private void updateControls() {
        if (sendButton == null || sendButton.isDisposed()) return;
        boolean connected = activeSession != null && activeSession.client != null;
        sendButton.setEnabled(connected && !activeSession.agentMessageOpen);
        stopButton.setEnabled(connected && activeSession.agentMessageOpen);
        closeButton.setEnabled(connected);
        boolean reviewFileChanges = connected && activeSession.reviewFileChanges;
        boolean hasDiffs = reviewFileChanges && !pendingDiffs(activeSession).isEmpty();
        applyButton.setEnabled(hasDiffs);
        rejectButton.setEnabled(hasDiffs);
        undoButton.setEnabled(reviewFileChanges && activeSession.diffApplier.canUndo());
        projectSelector.setEnabled(true);
    }

    private void closeActiveSession() {
        if (activeSession != null) {
            closeSession(activeSession);
        }
    }

    private void scrollTranscriptToBottom() {
        if (transcript == null || transcript.isDisposed()) {
            return;
        }
        transcript.execute("window.scrollTo(0, Math.max(document.body.scrollHeight, document.documentElement.scrollHeight));");
    }

    private void closeSession(ChatSession session) {
        AgentClient previousClient = session.client;
        session.client = null;
        if (previousClient != null) {
            // Closing a subprocess pipe can wait for a blocked reader. Never do it on SWT's UI thread.
            CompletableFuture.runAsync(previousClient::close);
        }
        int index = sessions.indexOf(session);
        if (index >= 0) {
            sessions.remove(index);
            if (projectSelector != null && !projectSelector.isDisposed()) projectSelector.remove(index);
        }
        if (activeSession == session) {
            activeSession = sessions.isEmpty() ? null : sessions.get(Math.min(index, sessions.size() - 1));
            if (activeSession != null) projectSelector.select(sessions.indexOf(activeSession));
            renderTranscript();
        }
        updateControls();
    }

    private void disconnect() {
        for (ChatSession session : new ArrayList<>(sessions)) {
            AgentClient previousClient = session.client;
            session.client = null;
            if (previousClient != null) CompletableFuture.runAsync(previousClient::close);
        }
        sessions.clear();
        activeSession = null;
        if (sendButton != null && !sendButton.isDisposed()) {
            sendButton.setEnabled(false);
        }
        if (stopButton != null && !stopButton.isDisposed()) {
            stopButton.setEnabled(false);
        }
        if (closeButton != null && !closeButton.isDisposed()) {
            closeButton.setEnabled(false);
        }
        if (applyButton != null && !applyButton.isDisposed()) applyButton.setEnabled(false);
        if (rejectButton != null && !rejectButton.isDisposed()) rejectButton.setEnabled(false);
        if (undoButton != null && !undoButton.isDisposed()) undoButton.setEnabled(false);
        if (projectSelector != null && !projectSelector.isDisposed()) {
            projectSelector.removeAll();
            projectSelector.setEnabled(true);
        }
    }

    private void ui(Runnable action) {
        Display display = getSite().getShell().getDisplay();
        if (display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            if (transcript != null && !transcript.isDisposed()) {
                action.run();
            }
        });
    }

    private static Throwable unwrap(Throwable error) {
        return error.getCause() == null ? error : error.getCause();
    }

    @Override
    public void setFocus() {
        prompt.setFocus();
    }

    @Override
    public void dispose() {
        disconnect();
        super.dispose();
    }
}
