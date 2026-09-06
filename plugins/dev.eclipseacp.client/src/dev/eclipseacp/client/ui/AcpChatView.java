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
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
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
    private Composite reviewBar;
    private Label reviewSummary;
    private Composite composer;
    private Combo projectSelector;
    private String chatFontFamily = "sans-serif";
    private int chatFontSizePoints = 10;
    private final List<ChatSession> sessions = new ArrayList<>();
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
        private String statusText = "Not connected";

        private ChatSession(IProject project, String label, String providerId, boolean reviewFileChanges) {
            this.project = project;
            this.label = label;
            this.providerId = providerId;
            this.reviewFileChanges = reviewFileChanges;
        }
    }

    @Override
    public void createPartControl(Composite parent) {
        GridLayout rootLayout = new GridLayout(1, false);
        rootLayout.marginWidth = 8;
        rootLayout.marginHeight = 8;
        rootLayout.verticalSpacing = 8;
        parent.setLayout(rootLayout);

        Composite header = new Composite(parent, SWT.NONE);
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout headerLayout = new GridLayout(4, false);
        headerLayout.marginWidth = 0;
        headerLayout.marginHeight = 0;
        header.setLayout(headerLayout);
        Label projectLabel = new Label(header, SWT.NONE);
        projectLabel.setText("Project:");
        projectSelector = new Combo(header, SWT.DROP_DOWN | SWT.READ_ONLY);
        projectSelector.setToolTipText("Project with its active ACP session");
        projectSelector.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        projectSelector.addListener(SWT.Selection, ignored -> selectProjectFromCombo());
        var fontData = projectSelector.getFont().getFontData();
        if (fontData.length > 0) {
            chatFontFamily = fontData[0].getName();
            chatFontSizePoints = Math.max(8, fontData[0].getHeight() - 1);
        }

        Composite sessionActions = new Composite(header, SWT.NONE);
        sessionActions.setLayout(new org.eclipse.swt.layout.RowLayout());
        Composite sessionMore = new Composite(header, SWT.NONE);
        sessionMore.setLayout(new org.eclipse.swt.layout.RowLayout());

        transcript = new Browser(parent, SWT.NONE);
        transcript.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        transcript.addProgressListener(new ProgressAdapter() {
            @Override
            public void completed(org.eclipse.swt.browser.ProgressEvent event) {
                if (activeSession != null) {
                    String document = new com.google.gson.Gson().toJson(chatDocument(activeSession.transcriptMarkdown.toString()));
                    transcript.execute("document.querySelector('main').innerHTML=new DOMParser().parseFromString("
                            + document + ", 'text/html').querySelector('main').innerHTML;");
                }
                scrollTranscriptToBottom();
            }
        });
        transcript.setText(chatDocument(""));

        reviewBar = new Composite(parent, SWT.NONE);
        reviewBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        reviewBar.setLayout(new GridLayout(4, false));
        reviewSummary = new Label(reviewBar, SWT.NONE);
        reviewSummary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        composer = new Composite(parent, SWT.BORDER);
        composer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout composerLayout = new GridLayout(1, false);
        composerLayout.marginWidth = 10;
        composerLayout.marginHeight = 8;
        composer.setLayout(composerLayout);
        prompt = new Text(composer, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        GridData promptData = new GridData(SWT.FILL, SWT.FILL, true, false);
        promptData.heightHint = 64;
        prompt.setLayoutData(promptData);
        prompt.setFont(projectSelector.getFont());
        prompt.setMessage("Ask anything, or add code context…");
        prompt.addModifyListener(event -> {
            int lines = Math.max(prompt.getLineCount(), prompt.getText().length()
                    / Math.max(20, prompt.getClientArea().width / 8) + 1);
            int height = Math.max(64, Math.min(160, lines * prompt.getLineHeight()));
            if (promptData.heightHint != height) {
                promptData.heightHint = height;
                composer.getParent().layout(true, true);
            }
            updateControls();
        });
        prompt.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent event) {
                if ((event.stateMask & SWT.MOD1) != 0 && (event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR)) {
                    event.doit = false;
                    sendPrompt();
                }
            }
        });

        Composite footer = new Composite(composer, SWT.NONE);
        footer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout footerLayout = new GridLayout(2, false);
        footerLayout.marginWidth = footerLayout.marginHeight = 0;
        footer.setLayout(footerLayout);
        Composite actions = new Composite(footer, SWT.NONE);
        actions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        org.eclipse.swt.layout.RowLayout actionsLayout = new org.eclipse.swt.layout.RowLayout();
        actionsLayout.wrap = true;
        actionsLayout.spacing = 3;
        actionsLayout.marginLeft = actionsLayout.marginRight = 0;
        actions.setLayout(actionsLayout);
        Composite submit = new Composite(footer, SWT.NONE);
        submit.setLayoutData(new GridData(SWT.END, SWT.BOTTOM, false, false));
        submit.setLayout(new GridLayout(1, false));

        sendButton = new Button(submit, SWT.PUSH);
        sendButton.setText("Send");
        sendButton.setToolTipText("Send message (Cmd/Ctrl+Enter)");
        sendButton.setEnabled(false);
        sendButton.addListener(SWT.Selection, ignored -> sendPrompt());

        stopButton = new Button(submit, SWT.PUSH);
        stopButton.setText("Stop");
        stopButton.setToolTipText("Stop generating the response");
        stopButton.setEnabled(false);
        stopButton.addListener(SWT.Selection, ignored -> cancel());

        newSessionButton = new Button(sessionActions, SWT.PUSH);
        newSessionButton.setToolTipText("New chat in this project");
        newSessionButton.setEnabled(false);
        newSessionButton.addListener(SWT.Selection, ignored -> openNewSessionForActiveProject());

        closeButton = new Button(sessionMore, SWT.PUSH);
        closeButton.setToolTipText("Close this chat");
        closeButton.setEnabled(false);
        closeButton.addListener(SWT.Selection, ignored -> closeActiveSession());

        historyButton = new Button(sessionActions, SWT.PUSH);
        historyButton.setText("History");
        historyButton.setToolTipText("Open chat history for this project");
        historyButton.setEnabled(false);
        historyButton.addListener(SWT.Selection, ignored -> chooseAgentSession());

        applyButton = new Button(reviewBar, SWT.PUSH);
        applyButton.setText("Apply changes");
        applyButton.setToolTipText("Apply the reviewed file changes");
        applyButton.setEnabled(false);
        applyButton.addListener(SWT.Selection, ignored -> applyChanges());

        rejectButton = new Button(reviewBar, SWT.PUSH);
        rejectButton.setText("Reject changes");
        rejectButton.setToolTipText("Reject the reviewed file changes");
        rejectButton.setEnabled(false);
        rejectButton.addListener(SWT.Selection, ignored -> rejectChanges());

        undoButton = new Button(reviewBar, SWT.PUSH);
        undoButton.setText("Undo apply");
        undoButton.setToolTipText("Undo the last applied file changes");
        undoButton.setEnabled(false);
        undoButton.addListener(SWT.Selection, ignored -> undoApply());

        contextButton = new Button(actions, SWT.PUSH);
        contextButton.setText("@ Context");
        contextButton.setToolTipText("Insert @file, @selection, @java, @problems and @console references");
        contextButton.setEnabled(false);
        contextButton.addListener(SWT.Selection, ignored -> addContextReferences());

        commandsButton = new Button(actions, SWT.PUSH);
        commandsButton.setText("Commands");
        commandsButton.setToolTipText("Agent slash commands");
        commandsButton.setEnabled(false);
        commandsButton.addListener(SWT.Selection, ignored -> chooseCommand());

        settingsButton = new Button(actions, SWT.PUSH);
        settingsButton.setText("Options…");
        settingsButton.setToolTipText("Configure agent options");
        settingsButton.setEnabled(false);
        settingsButton.addListener(SWT.Selection, ignored -> editConfigOption());

        attachButton = new Button(actions, SWT.PUSH);
        attachButton.setText("Attach");
        attachButton.setToolTipText("Attach an image or audio file to the next prompt");
        attachButton.setEnabled(false);
        attachButton.addListener(SWT.Selection, ignored -> attachFile());

        applyButtonImages();
        status = new Label(parent, SWT.NONE);
        status.setText("Not connected");
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        updateControls();
    }

    /** Called only by the project/resource context-menu command. */
    public void openSessionFor(IProject project) {
        openSessionFor(project, null);
    }

    /** Selects a project's active session, or opens its first session for this view lifetime. */
    public void openSessionFor(IProject project, String initialPrompt) {
        if (project == null || !project.exists() || !project.isOpen() || project.getLocation() == null) {
            onError("Cannot open ACP session", new IllegalArgumentException("The selected project is not open"));
            return;
        }
        ChatSession existing = sessionFor(project);
        if (existing != null) {
            selectSession(existing);
            if (initialPrompt != null && !initialPrompt.isBlank()) sendPrompt(existing, initialPrompt);
            return;
        }
        AgentProvider provider = new AgentProviderRegistry(AcpPreferences.store()).active();
        String agentName = provider.name();

        boolean reviewFileChanges = AcpPreferences.store().getBoolean(AcpPreferences.REVIEW_FILE_CHANGES);
        ChatSession session = new ChatSession(project, project.getName(), provider.id(), reviewFileChanges);
        session.initialPrompt = initialPrompt;
        sessions.add(session);
        projectSelector.add(session.label);
        activeSession = session;
        projectSelector.select(projectSelector.getItemCount() - 1);
        renderTranscript();
        renderStatus();
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
            if (restored) {
                session.acceptingRestoredTranscript = false;
                setStatus(session, "Session restored");
            }
            updateControls();
            if (session.initialPrompt != null && !session.initialPrompt.isBlank()) {
                String initial = session.initialPrompt;
                session.initialPrompt = null;
                sendPrompt(session, initial);
            } else if (activeSession == session) prompt.setFocus();
        }));
    }

    private void selectProjectFromCombo() {
        int index = projectSelector.getSelectionIndex();
        if (index < 0 || index >= sessions.size()) return;
        selectSession(sessions.get(index));
    }

    private ChatSession sessionFor(IProject project) {
        return sessions.stream().filter(session -> session.project.equals(project)).findFirst().orElse(null);
    }

    private void selectSession(ChatSession session) {
        activeSession = session;
        int index = sessions.indexOf(session);
        if (index >= 0 && projectSelector != null && !projectSelector.isDisposed()) projectSelector.select(index);
        renderTranscript();
        renderStatus();
        updateControls();
    }

    private void sendPrompt() {
        String text = prompt.getText().trim();
        ChatSession session = activeSession;
        if (text.isEmpty() || session == null || session.client == null || session.agentMessageOpen) {
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
        AgentProvider provider = providerFor(previous.providerId);
        ChatSession replacement = new ChatSession(previous.project, previous.project.getName(), provider.id(),
                AcpPreferences.store().getBoolean(AcpPreferences.REVIEW_FILE_CHANGES));
        replaceSession(previous, replacement);
        connect(replacement, provider, null, false, provider.name());
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
                    dialog.setTitle("ACP session history");
                    dialog.setMessage("Select a session for " + session.project.getName() + ":");
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
                    restoreListedSession(session, (SessionInfo) dialog.getResult()[0]);
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

    private void restoreListedSession(ChatSession session, SessionInfo selected) {
        AgentProvider provider = providerFor(session.providerId);
        ChatSession restored = new ChatSession(session.project, session.project.getName(), provider.id(),
                AcpPreferences.store().getBoolean(AcpPreferences.REVIEW_FILE_CHANGES));
        restored.acceptingRestoredTranscript = true;
        replaceSession(session, restored);
        connect(restored, provider, selected.id(), true, provider.name());
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
            session.attachments.add(new PromptAttachment(path, mime)); setStatus(session, "Attachment ready: " + path.getFileName()); updateControls();
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
            if (session == null) return;
            session.statusText = value == null || value.isBlank() ? "Not connected" : value;
            if (session == activeSession) renderStatus();
        });
    }

    /** The status bar follows the selected project's session, just like the transcript. */
    private void renderStatus() {
        if (status == null || status.isDisposed()) return;
        status.setText(activeSession == null ? "Not connected" : activeSession.statusText);
        status.getParent().layout();
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
        if (session != activeSession || transcript == null || transcript.isDisposed()) return;
        String document = new com.google.gson.Gson().toJson(chatDocument(session.transcriptMarkdown.toString()));
        // Keep the document alive during streaming and follow each agent chunk.
        boolean updated = transcript.execute("if(document.querySelector('main')){"
                + "var next=new DOMParser().parseFromString(" + document + ", 'text/html');"
                + "document.querySelector('main').innerHTML=next.querySelector('main').innerHTML;"
                + "window.scrollTo(0,Math.max(document.body.scrollHeight,document.documentElement.scrollHeight));}");
        if (!updated) transcript.setText(chatDocument(session.transcriptMarkdown.toString()));
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
        if (transcript == null || transcript.isDisposed()) return;
        transcript.setText(chatDocument(activeSession == null ? "" : activeSession.transcriptMarkdown.toString()));
        scrollTranscriptToBottom();
    }

    private String chatDocument(String markdown) {
        return GfmRenderer.document(markdown, chatFontFamily, chatFontSizePoints);
    }

    private void applyButtonImages() {
        ISharedImages images = PlatformUI.getWorkbench().getSharedImages();
        sendButton.setImage(images.getImage(ISharedImages.IMG_TOOL_FORWARD));
        stopButton.setImage(images.getImage(ISharedImages.IMG_ELCL_STOP));
        newSessionButton.setImage(images.getImage(ISharedImages.IMG_OBJ_ADD));
        closeButton.setImage(images.getImage(ISharedImages.IMG_ELCL_REMOVE));
        historyButton.setImage(images.getImage(ISharedImages.IMG_OBJ_FOLDER));
        applyButton.setImage(images.getImage(ISharedImages.IMG_ETOOL_SAVE_EDIT));
        rejectButton.setImage(images.getImage(ISharedImages.IMG_ETOOL_DELETE));
        undoButton.setImage(images.getImage(ISharedImages.IMG_TOOL_UNDO));
        contextButton.setImage(images.getImage(ISharedImages.IMG_OBJ_ADD));
        commandsButton.setImage(images.getImage(ISharedImages.IMG_OBJS_INFO_TSK));
        settingsButton.setImage(images.getImage(ISharedImages.IMG_OBJ_ELEMENT));
        attachButton.setImage(images.getImage(ISharedImages.IMG_OBJ_FILE));
    }

    private static void showControl(org.eclipse.swt.widgets.Control control, boolean visible) {
        control.setVisible(visible);
        if (control.getParent().getLayout() instanceof GridLayout) {
            GridData data = control.getLayoutData() instanceof GridData existing ? existing : new GridData();
            data.exclude = !visible;
            control.setLayoutData(data);
        } else {
            org.eclipse.swt.layout.RowData data = new org.eclipse.swt.layout.RowData();
            data.exclude = !visible;
            control.setLayoutData(data);
        }
    }

    private void updateControls() {
        if (sendButton == null || sendButton.isDisposed()) return;
        boolean connected = activeSession != null && activeSession.client != null;
        sendButton.setEnabled(connected && !activeSession.agentMessageOpen && !prompt.getText().isBlank());
        boolean busy = connected && activeSession.agentMessageOpen;
        showControl(sendButton, !busy);
        showControl(stopButton, busy);
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
        showControl(commandsButton, commandsButton.getEnabled());
        showControl(settingsButton, settingsButton.getEnabled());
        showControl(reviewBar, hasDiffs || undoButton.getEnabled());
        showControl(applyButton, hasDiffs);
        showControl(rejectButton, hasDiffs);
        showControl(undoButton, undoButton.getEnabled());
        reviewSummary.setText(hasDiffs ? pendingDiffs(activeSession).size() + " changed files" : "Changes applied");
        attachButton.setText(connected && !activeSession.attachments.isEmpty()
                ? "Attach (" + activeSession.attachments.size() + ")" : "Attach");
        prompt.setEnabled(connected);
        projectSelector.setEnabled(connected || !sessions.isEmpty());
        composer.getParent().layout(true, true);
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
        retire(session);
        int index = sessions.indexOf(session);
        if (index >= 0) {
            sessions.remove(index);
            if (projectSelector != null && !projectSelector.isDisposed()) projectSelector.remove(index);
        }
        if (activeSession == session) {
            activeSession = sessions.isEmpty() ? null : sessions.get(Math.min(index, sessions.size() - 1));
            if (activeSession != null) projectSelector.select(sessions.indexOf(activeSession));
            renderTranscript();
            renderStatus();
        }
        updateControls();
    }

    private AgentProvider providerFor(String providerId) {
        AgentProviderRegistry registry = new AgentProviderRegistry(AcpPreferences.store());
        return registry.list().stream().filter(provider -> provider.id().equals(providerId)).findFirst()
                .orElseGet(registry::active);
    }

    /** Replaces the active ACP conversation without changing the project entry in the selector. */
    private void replaceSession(ChatSession previous, ChatSession replacement) {
        int index = sessions.indexOf(previous);
        if (index < 0) return;
        retire(previous);
        sessions.set(index, replacement);
        activeSession = replacement;
        projectSelector.select(index);
        renderTranscript();
        renderStatus();
        updateControls();
    }

    /** session/close gives agents a chance to durably store the conversation. */
    private void retire(ChatSession session) {
        AgentClient previousClient = session.client;
        session.client = null;
        if (previousClient != null) CompletableFuture.runAsync(previousClient::close);
    }

    private void disconnect() {
        for (ChatSession session : new ArrayList<>(sessions)) {
            AgentClient previousClient = session.client;
            session.client = null;
            if (previousClient != null) CompletableFuture.runAsync(previousClient::close);
        }
        sessions.clear();
        activeSession = null;
        renderStatus();
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
