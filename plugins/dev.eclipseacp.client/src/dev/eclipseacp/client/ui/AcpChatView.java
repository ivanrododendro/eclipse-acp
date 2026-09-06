package dev.eclipseacp.client.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
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
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.dialogs.ListDialog;

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
import dev.eclipseacp.client.agent.SessionInfo;
import dev.eclipseacp.client.agent.PromptAttachment;
import dev.eclipseacp.client.agent.ConfigOption;
import dev.eclipseacp.client.preferences.AgentProviderRegistry;
import dev.eclipseacp.client.preferences.AcpPreferences;
import dev.eclipseacp.client.mcp.McpServerRegistry;

public final class AcpChatView extends ViewPart implements AcpListener {
    public static final String ID = "dev.eclipseacp.client.views.chat";
    private Browser transcript;
    private Text prompt;
    private Button sendButton;
    private Button stopButton;
    private Button newSessionButton;
    private Button closeButton;
    private Button historyButton;
    private Button applyButton;
    private Button rejectButton;
    private Button undoButton;
    private Button contextButton;
    private Button commandsButton;
    private Button settingsButton;
    private Button attachButton;
    private Label status;
    private Combo projectSelector;
    private final List<ChatSession> sessions = new ArrayList<>();
    private final SessionHistoryStore sessionHistory = new SessionHistoryStore();
    private final Map<String, SessionHistoryStore.Entry> savedSessions = new LinkedHashMap<>();
    private ChatSession activeSession;

    private static final class ChatSession {
        private final IProject project;
        private final String label;
        private final String providerId;
        private final boolean reviewFileChanges;
        private final StringBuilder transcriptMarkdown = new StringBuilder();
        private AgentClient client;
        private boolean agentMessageOpen;
        private boolean acceptingRestoredTranscript;
        private boolean restoredAgentMessageOpen;
        private final Map<String, ToolCall> toolCalls = new LinkedHashMap<>();
        private final Map<String, FileDiff> pendingChanges = new LinkedHashMap<>();
        private final WorkspaceDiffApplier diffApplier = new WorkspaceDiffApplier();
        private final List<PromptAttachment> attachments = new ArrayList<>();
        private final Map<String, String> commands = new LinkedHashMap<>();
        private final Map<String, ConfigOption> configOptions = new LinkedHashMap<>();

        private String persistedSessionId;
        private String initialPrompt;

        private ChatSession(IProject project, String label, String providerId, boolean reviewFileChanges) {
            this.project = project;
            this.label = label;
            this.providerId = providerId;
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
        actions.setLayout(new GridLayout(13, false));

        sendButton = new Button(actions, SWT.PUSH);
        sendButton.setText("Send");
        sendButton.setEnabled(false);
        sendButton.addListener(SWT.Selection, ignored -> sendPrompt());

        stopButton = new Button(actions, SWT.PUSH);
        stopButton.setText("Stop");
        stopButton.setEnabled(false);
        stopButton.addListener(SWT.Selection, ignored -> cancel());

        newSessionButton = new Button(actions, SWT.PUSH);
        newSessionButton.setText("New session");
        newSessionButton.setEnabled(false);
        newSessionButton.addListener(SWT.Selection, ignored -> openNewSessionForActiveProject());

        closeButton = new Button(actions, SWT.PUSH);
        closeButton.setText("Close");
        closeButton.setEnabled(false);
        closeButton.addListener(SWT.Selection, ignored -> closeActiveSession());

        historyButton = new Button(actions, SWT.PUSH);
        historyButton.setText("Sessions…");
        historyButton.setEnabled(false);
        historyButton.addListener(SWT.Selection, ignored -> chooseAgentSession());

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

        contextButton = new Button(actions, SWT.PUSH);
        contextButton.setText("Add context");
        contextButton.setToolTipText("Insert @file, @selection, @java, @problems and @console references");
        contextButton.setEnabled(false);
        contextButton.addListener(SWT.Selection, ignored -> addContextReferences());

        commandsButton = new Button(actions, SWT.PUSH);
        commandsButton.setText("Commands…");
        commandsButton.setEnabled(false);
        commandsButton.addListener(SWT.Selection, ignored -> chooseCommand());

        settingsButton = new Button(actions, SWT.PUSH);
        settingsButton.setText("Options…");
        settingsButton.setEnabled(false);
        settingsButton.addListener(SWT.Selection, ignored -> editConfigOption());

        attachButton = new Button(actions, SWT.PUSH);
        attachButton.setText("Attach…");
        attachButton.setToolTipText("Attach an image or audio file to the next prompt");
        attachButton.setEnabled(false);
        attachButton.addListener(SWT.Selection, ignored -> attachFile());

        status = new Label(actions, SWT.NONE);
        status.setText("Not connected");
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        restoreSavedSessions();
    }

    /** Called only by the project/resource context-menu command. */
    public void openSessionFor(IProject project) {
        openSessionFor(project, null);
    }

    /** Opens a project session and, once connected, sends an explicit contextual action prompt. */
    public void openSessionFor(IProject project, String initialPrompt) {
        if (project == null || !project.exists() || !project.isOpen() || project.getLocation() == null) {
            onError("Cannot open ACP session", new IllegalArgumentException("The selected project is not open"));
            return;
        }
        AgentProvider provider = new AgentProviderRegistry(AcpPreferences.store()).active();
        String agentName = provider.name();

        boolean reviewFileChanges = AcpPreferences.store().getBoolean(AcpPreferences.REVIEW_FILE_CHANGES);
        ChatSession session = new ChatSession(project, sessionLabel(project), provider.id(), reviewFileChanges);
        session.initialPrompt = initialPrompt;
        sessions.add(session);
        projectSelector.add(session.label);
        activeSession = session;
        projectSelector.select(projectSelector.getItemCount() - 1);
        renderTranscript();
        append(session, "Connecting to " + agentName + " in " + project.getLocation() + "…"
                + (reviewFileChanges ? " Changes will be reviewed before applying." : " Changes apply immediately.") + "\n\n");
        connect(session, provider, null, false, agentName);
    }

    private void connect(ChatSession session, AgentProvider provider, String restoredSessionId,
            boolean restored, String agentName) {
        AgentClient newClient = AgentClientFactory.create(provider, listenerFor(session), session.reviewFileChanges,
                new McpServerRegistry(AcpPreferences.store()).forSession(provider.id(), session.project.getName()));
        session.client = newClient;
        CompletableFuture<Void> connection = restoredSessionId == null
                ? newClient.connect(session.project.getLocation().toFile().toPath())
                : newClient.restoreSession(restoredSessionId, session.project.getLocation().toFile().toPath());
        connection.whenComplete((ignored, error) -> ui(() -> {
            if (session.client != newClient) {
                return; // A newer connection replaced this one.
            }
            if (error != null) {
                onError(session, "Could not start " + agentName, unwrap(error));
                if (!restored) closeSession(session);
                return;
            }
            session.persistedSessionId = newClient.sessionId();
            persistSessions();
            updateControls();
            if (session.initialPrompt != null && !session.initialPrompt.isBlank()) {
                String initial = session.initialPrompt;
                session.initialPrompt = null;
                sendPrompt(session, initial);
            } else if (activeSession == session) prompt.setFocus();
        }));
    }

    private void restoreSavedSessions() {
        List<AgentProvider> providers = new AgentProviderRegistry(AcpPreferences.store()).list();
        List<SessionHistoryStore.Entry> entries = sessionHistory.load();
        for (SessionHistoryStore.Entry entry : entries) {
            savedSessions.put(sessionKey(entry), entry);
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(entry.projectName());
            if (!project.exists() || !project.isOpen() || project.getLocation() == null
                    || !project.getLocation().toString().equals(entry.projectPath())) continue;
            AgentProvider provider = providers.stream().filter(candidate -> candidate.id().equals(entry.providerId()))
                    .findFirst().orElse(null);
            if (provider == null) continue;
            ChatSession session = new ChatSession(project, entry.label(), entry.providerId(),
                    AcpPreferences.store().getBoolean(AcpPreferences.REVIEW_FILE_CHANGES));
            session.persistedSessionId = entry.sessionId();
            session.transcriptMarkdown.append(entry.transcript() == null ? "" : entry.transcript());
            sessions.add(session);
            projectSelector.add(session.label);
            if (activeSession == null) activeSession = session;
            connect(session, provider, entry.sessionId(), true, provider.name());
        }
        if (activeSession != null) {
            projectSelector.select(sessions.indexOf(activeSession));
            renderTranscript();
            updateControls();
        }
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
        sendPrompt(session, text);
    }

    private void sendPrompt(ChatSession session, String text) {
        if (session == null || session.client == null || text == null || text.isBlank()) return;
        String expanded = EclipseContext.expand(text, session.project, getSite().getPage());
        session.agentMessageOpen = false;
        session.restoredAgentMessageOpen = false;
        append(session, "## You\n\n" + expanded + "\n\n## Agent\n\n");
        session.agentMessageOpen = true;
        updateControls();

        AgentClient activeClient = session.client;
        List<PromptAttachment> attachments = List.copyOf(session.attachments);
        session.attachments.clear();
        if (!attachments.isEmpty()) {
            append(session, "> Attached: " + attachments.stream().map(item -> "`" + item.path().getFileName() + "`")
                    .collect(java.util.stream.Collectors.joining(", ")) + "\n\n");
        }
        activeClient.prompt(expanded, attachments).whenComplete((ignored, error) -> ui(() -> {
            if (session.client != activeClient) {
                return; // The response belongs to an earlier connection.
            }
            if (error != null) {
                session.attachments.addAll(attachments);
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

    private void openNewSessionForActiveProject() {
        ChatSession previous = activeSession;
        if (previous == null) return;
        IProject project = previous.project;
        // ACP agents commonly persist the durable session record on session/close.
        // closeSession also retains its local history entry before removing the tab.
        closeSession(previous);
        openSessionFor(project);
    }

    private void chooseAgentSession() {
        ChatSession session = activeSession;
        if (session == null || session.client == null) return;
        AgentClient client = session.client;
        listAllSessions(client, session.project.getLocation().toFile().toPath(), null, new ArrayList<>())
                .whenComplete((available, error) -> ui(() -> {
                    if (session.client != client) return;
                    if (error != null) {
                        onError(session, "Could not list agent sessions", unwrap(error));
                        return;
                    }
                    ListDialog dialog = new ListDialog(getSite().getShell());
                    dialog.setTitle("ACP sessions");
                    dialog.setMessage("Select a session to restore:");
                    dialog.setContentProvider(ArrayContentProvider.getInstance());
                    dialog.setLabelProvider(new LabelProvider() {
                        @Override public String getText(Object element) {
                            SessionInfo info = (SessionInfo) element;
                            String title = info.title().isBlank() ? info.id() : info.title();
                            return info.updatedAt().isBlank() ? title : title + " — " + info.updatedAt();
                        }
                    });
                    List<SessionInfo> projectSessions = available.stream()
                            .filter(info -> belongsToProject(info, session.project.getLocation().toFile().toPath()))
                            .toList();
                    if (projectSessions.isEmpty()) {
                        setStatus(session, "No saved sessions for this project");
                        return;
                    }
                    dialog.setInput(projectSessions);
                    if (dialog.open() != org.eclipse.jface.window.Window.OK
                            || dialog.getResult() == null || dialog.getResult().length == 0) return;
                    restoreListedSession(session, client, (SessionInfo) dialog.getResult()[0]);
                }));
    }

    private CompletableFuture<List<SessionInfo>> listAllSessions(AgentClient client, java.nio.file.Path cwd,
            String cursor, List<SessionInfo> collected) {
        return client.listSessions(cwd, cursor).thenCompose(page -> {
            collected.addAll(page.sessions());
            return page.nextCursor() == null || page.nextCursor().isBlank()
                    ? CompletableFuture.completedFuture(List.copyOf(collected))
                    : listAllSessions(client, cwd, page.nextCursor(), collected);
        });
    }

    private static boolean belongsToProject(SessionInfo info, java.nio.file.Path projectDirectory) {
        try {
            return java.nio.file.Path.of(info.cwd()).toAbsolutePath().normalize()
                    .equals(projectDirectory.toAbsolutePath().normalize());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void restoreListedSession(ChatSession session, AgentClient client, SessionInfo selected) {
        String oldSessionId = session.persistedSessionId;
        String oldTranscript = session.transcriptMarkdown.toString();
        session.transcriptMarkdown.setLength(0);
        session.acceptingRestoredTranscript = true;
        session.restoredAgentMessageOpen = false;
        renderTranscript();
        CompletableFuture<Void> restored = client.capabilities().sessionResume()
                ? client.resumeSession(selected.id(), session.project.getLocation().toFile().toPath())
                : client.loadSession(selected.id(), session.project.getLocation().toFile().toPath());
        restored.whenComplete((ignored, error) -> ui(() -> {
            if (session.client != client) return;
            if (error != null) {
                session.acceptingRestoredTranscript = false;
                session.persistedSessionId = oldSessionId;
                session.transcriptMarkdown.append(oldTranscript);
                renderTranscript();
                onError(session, "Could not restore the selected session", unwrap(error));
                return;
            }
            session.acceptingRestoredTranscript = false;
            session.persistedSessionId = selected.id();
            persistSessions();
            setStatus(session, "Session restored");
        }));
    }

    private AcpListener listenerFor(ChatSession session) {
        return new AcpListener() {
            @Override public void onAgentText(String text) { ui(() -> appendAgentText(session, text)); }
            @Override public void onUserText(String text) { ui(() -> appendRestoredUserText(session, text)); }
            @Override public void onStatus(String value) { setStatus(session, value); }
            @Override public void onError(String message, Throwable error) { AcpChatView.this.onError(session, message, error); }
            @Override public CompletableFuture<String> requestPermission(String title, List<PermissionOption> options) {
                return requestPermissionFor(session, title, options);
            }
            @Override public CompletableFuture<String> requestPermission(PermissionRequest request) {
                return requestPermissionFor(session, request);
            }
            @Override public void onToolCall(ToolCall toolCall) { ui(() -> updateToolCall(session, toolCall)); }
            @Override public void onSessionUpdate(dev.eclipseacp.client.acp.AcpSessionUpdate update) {
                ui(() -> renderExperienceUpdate(session, update));
            }
            @Override public CompletableFuture<JsonObject> requestElicitation(JsonObject request) {
                return requestElicitationFor(session, request);
            }
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

    private void renderExperienceUpdate(ChatSession session, dev.eclipseacp.client.acp.AcpSessionUpdate update) {
        JsonObject payload = update.payload();
        switch (update.kind()) {
        case "available_commands_update" -> {
            session.commands.clear();
            JsonElement commands = payload.has("availableCommands") ? payload.get("availableCommands") : payload.get("commands");
            if (commands != null && commands.isJsonArray()) for (JsonElement element : commands.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject command = element.getAsJsonObject();
                String name = jsonString(command, "name");
                if (name.isBlank()) name = jsonString(command, "command");
                if (!name.isBlank()) session.commands.put(name, jsonString(command, "description"));
            }
            setStatus(session, session.commands.isEmpty() ? "No slash commands" : session.commands.size() + " slash command(s) available");
        }
        case "config_option_update" -> {
            JsonElement options = payload.has("configOptions") ? payload.get("configOptions") : payload;
            if (options.isJsonArray()) for (JsonElement element : options.getAsJsonArray()) {
                if (element.isJsonObject()) storeConfigOption(session, element.getAsJsonObject());
            } else if (options.isJsonObject()) storeConfigOption(session, options.getAsJsonObject());
        }
        case "usage_update" -> append(session, "> **Usage:** " + usageText(payload) + "\n\n");
        case "terminal_output", "terminal_output_update" -> appendTerminalOutput(session, payload);
        default -> { }
        }
        updateControls();
    }

    private static void storeConfigOption(ChatSession session, JsonObject option) {
        String id = jsonString(option, "configId");
        if (id.isBlank()) id = jsonString(option, "id");
        if (!id.isBlank()) session.configOptions.put(id, new ConfigOption(id, nonBlank(jsonString(option, "name"), id),
                jsonString(option, "description"), option.has("value") ? option.get("value").deepCopy() : null));
    }

    private static String usageText(JsonObject payload) {
        List<String> entries = new ArrayList<>();
        for (String key : List.of("inputTokens", "outputTokens", "totalTokens", "cost")) {
            if (payload.has(key) && payload.get(key).isJsonPrimitive()) entries.add(key + "=" + payload.get(key).getAsString());
        }
        return entries.isEmpty() ? "updated" : String.join(", ", entries);
    }

    private void appendTerminalOutput(ChatSession session, JsonObject payload) {
        String output = jsonString(payload, "output");
        if (output.isBlank()) output = jsonString(payload, "text");
        if (!output.isBlank()) append(session, "> **Terminal output**\n\n```text\n" + output + "\n```\n\n");
    }

    private void chooseCommand() {
        ChatSession session = activeSession;
        if (session == null || session.commands.isEmpty()) return;
        ListDialog dialog = new ListDialog(getSite().getShell());
        dialog.setTitle("ACP commands"); dialog.setMessage("Insert a slash command:");
        dialog.setContentProvider(ArrayContentProvider.getInstance());
        dialog.setLabelProvider(new LabelProvider() { @Override public String getText(Object value) {
            String name = (String) value; String description = session.commands.get(name);
            return "/" + name + (description.isBlank() ? "" : " — " + description);
        }});
        dialog.setInput(session.commands.keySet());
        if (dialog.open() == org.eclipse.jface.window.Window.OK && dialog.getResult() != null && dialog.getResult().length > 0) {
            prompt.insert("/" + dialog.getResult()[0] + " "); prompt.setFocus();
        }
    }

    private void editConfigOption() {
        ChatSession session = activeSession;
        if (session == null || session.configOptions.isEmpty() || session.client == null) return;
        ListDialog picker = new ListDialog(getSite().getShell()); picker.setTitle("ACP options"); picker.setMessage("Choose an option to edit:");
        picker.setContentProvider(ArrayContentProvider.getInstance()); picker.setLabelProvider(new LabelProvider() { @Override public String getText(Object value) {
            ConfigOption option = (ConfigOption) value; return option.name() + (option.description().isBlank() ? "" : " — " + option.description());
        }}); picker.setInput(session.configOptions.values());
        if (picker.open() != org.eclipse.jface.window.Window.OK || picker.getResult() == null || picker.getResult().length == 0) return;
        ConfigOption option = (ConfigOption) picker.getResult()[0];
        String current = option.value() == null ? "" : option.value().toString();
        InputDialog input = new InputDialog(getSite().getShell(), option.name(), option.description(), current, null);
        if (input.open() != org.eclipse.jface.window.Window.OK) return;
        JsonElement value;
        try { value = JsonParser.parseString(input.getValue()); } catch (RuntimeException error) { value = new com.google.gson.JsonPrimitive(input.getValue()); }
        session.client.setConfigOption(option.id(), value).whenComplete((ignored, error) -> ui(() -> {
            if (error != null) onError(session, "Could not update " + option.name(), unwrap(error));
        }));
    }

    private void attachFile() {
        ChatSession session = activeSession; if (session == null) return;
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.OPEN); dialog.setText("Attach image or audio");
        dialog.setFilterExtensions(new String[] { "*.png;*.jpg;*.jpeg;*.gif;*.webp;*.mp3;*.wav;*.ogg", "*.*" });
        String selected = dialog.open(); if (selected == null) return;
        Path path = Path.of(selected);
        try {
            if (Files.size(path) > 10 * 1024 * 1024) { MessageDialog.openWarning(getSite().getShell(), "Attachment too large", "Attachments are limited to 10 MiB."); return; }
            String mime = Files.probeContentType(path); if (mime == null || !(mime.startsWith("image/") || mime.startsWith("audio/"))) {
                MessageDialog.openWarning(getSite().getShell(), "Unsupported attachment", "Choose an image or audio file."); return;
            }
            session.attachments.add(new PromptAttachment(path, mime)); setStatus(session, "Attachment ready: " + path.getFileName());
        } catch (IOException error) { onError(session, "Could not attach file", error); }
    }

    private CompletableFuture<JsonObject> requestElicitationFor(ChatSession session, JsonObject request) {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        ui(() -> {
            String title = nonBlank(jsonString(request, "message"), nonBlank(jsonString(request, "title"), "Agent input required"));
            InputDialog dialog = new InputDialog(getSite().getShell(), "ACP input", title, "", null);
            if (dialog.open() != org.eclipse.jface.window.Window.OK) { result.complete(new JsonObject()); return; }
            JsonObject answer = new JsonObject(); answer.addProperty("value", dialog.getValue()); result.complete(answer);
        });
        return result;
    }

    private static String jsonString(JsonObject value, String key) { return value.has(key) && value.get(key).isJsonPrimitive() ? value.get(key).getAsString() : ""; }
    private static String nonBlank(String first, String fallback) { return first == null || first.isBlank() ? fallback : first; }

    @Override
    public void onAgentText(String text) {
        ui(() -> appendAgentText(activeSession, text));
    }

    @Override
    public void onUserText(String text) {
        ui(() -> appendRestoredUserText(activeSession, text));
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
        if (toolCall.kind().toLowerCase(java.util.Locale.ROOT).contains("terminal") && toolCall.rawOutput() instanceof JsonObject output) {
            appendTerminalOutput(session, output);
        }
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

    private void addContextReferences() {
        if (activeSession == null) return;
        String references = "@file\n@selection\n@java\n@problems\n@console\n";
        prompt.insert(references);
        prompt.setFocus();
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
        persistSessions();
        if (session != activeSession || transcript == null || transcript.isDisposed()) return;
        transcript.setText(GfmRenderer.document(session.transcriptMarkdown.toString()));
        // setText starts an asynchronous page load; the progress listener above
        // repeats this after the new document has been laid out.
        scrollTranscriptToBottom();
    }

    private void appendRestoredUserText(ChatSession session, String text) {
        if (session == null || !session.acceptingRestoredTranscript || text.isEmpty()) return;
        append(session, "## You\n\n" + text + "\n\n");
        session.restoredAgentMessageOpen = false;
    }

    private void appendAgentText(ChatSession session, String text) {
        if (session == null || text.isEmpty()) return;
        if (session.acceptingRestoredTranscript && !session.restoredAgentMessageOpen) {
            append(session, "## Agent\n\n");
            session.restoredAgentMessageOpen = true;
        }
        append(session, text);
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
        newSessionButton.setEnabled(connected && activeSession.project.isOpen());
        closeButton.setEnabled(connected);
        historyButton.setEnabled(connected && activeSession.client.capabilities().sessionList()
                && (activeSession.client.capabilities().sessionResume() || activeSession.client.capabilities().loadSession()));
        boolean reviewFileChanges = connected && activeSession.reviewFileChanges;
        boolean hasDiffs = reviewFileChanges && !pendingDiffs(activeSession).isEmpty();
        applyButton.setEnabled(hasDiffs);
        rejectButton.setEnabled(hasDiffs);
        undoButton.setEnabled(reviewFileChanges && activeSession.diffApplier.canUndo());
        contextButton.setEnabled(connected);
        commandsButton.setEnabled(connected && !activeSession.commands.isEmpty());
        settingsButton.setEnabled(connected && !activeSession.configOptions.isEmpty());
        attachButton.setEnabled(connected);
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
        persistSessions();
        updateControls();
    }

    private void persistSessions() {
        for (ChatSession session : sessions) {
            if (session.persistedSessionId == null || session.persistedSessionId.isBlank()) continue;
            SessionHistoryStore.Entry entry = new SessionHistoryStore.Entry(session.providerId, session.project.getName(),
                    session.project.getLocation().toString(), session.persistedSessionId, session.label,
                    session.transcriptMarkdown.toString());
            savedSessions.put(sessionKey(entry), entry);
        }
        sessionHistory.save(List.copyOf(savedSessions.values()));
    }

    private static String sessionKey(SessionHistoryStore.Entry entry) {
        return entry.providerId() + '\u0000' + entry.projectPath() + '\u0000' + entry.sessionId();
    }

    private void disconnect() {
        for (ChatSession session : new ArrayList<>(sessions)) {
            AgentClient previousClient = session.client;
            session.client = null;
            if (previousClient != null) CompletableFuture.runAsync(previousClient::close);
        }
        sessions.clear();
        activeSession = null;
        // Deliberately retain history: closing Eclipse must not discard resumable ACP sessions.
        if (sendButton != null && !sendButton.isDisposed()) {
            sendButton.setEnabled(false);
        }
        if (stopButton != null && !stopButton.isDisposed()) {
            stopButton.setEnabled(false);
        }
        if (newSessionButton != null && !newSessionButton.isDisposed()) newSessionButton.setEnabled(false);
        if (closeButton != null && !closeButton.isDisposed()) {
            closeButton.setEnabled(false);
        }
        if (historyButton != null && !historyButton.isDisposed()) historyButton.setEnabled(false);
        if (applyButton != null && !applyButton.isDisposed()) applyButton.setEnabled(false);
        if (rejectButton != null && !rejectButton.isDisposed()) rejectButton.setEnabled(false);
        if (undoButton != null && !undoButton.isDisposed()) undoButton.setEnabled(false);
        if (contextButton != null && !contextButton.isDisposed()) contextButton.setEnabled(false);
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
