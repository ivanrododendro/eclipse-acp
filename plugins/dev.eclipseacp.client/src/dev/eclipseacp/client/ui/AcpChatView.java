package dev.eclipseacp.client.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;


import org.eclipse.core.resources.IProject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.ImageRegistry;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.LocationAdapter;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
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

import dev.eclipseacp.client.AcpLog;
import dev.eclipseacp.client.agent.AgentClient;
import dev.eclipseacp.client.agent.AgentCommand;
import dev.eclipseacp.client.agent.AuthMethod;
import dev.eclipseacp.client.agent.ConfigValue;
import dev.eclipseacp.client.agent.FileDiff;
import dev.eclipseacp.client.agent.FileReadRequest;
import dev.eclipseacp.client.agent.FileWriteRequest;
import dev.eclipseacp.client.agent.PermissionOption;
import dev.eclipseacp.client.agent.PermissionRequest;
import dev.eclipseacp.client.agent.ElicitationRequest;
import dev.eclipseacp.client.agent.Usage;
import dev.eclipseacp.client.agent.ToolCall;
import dev.eclipseacp.client.agent.AgentProvider;
import dev.eclipseacp.client.agent.SessionInfo;
import dev.eclipseacp.client.agent.PromptAttachment;
import dev.eclipseacp.client.agent.ConfigOption;

public final class AcpChatView extends ViewPart {
    public static final String ID = "dev.eclipseacp.client.views.chat";
    private Browser transcript;
    private Menu transcriptMenu;
    private MenuItem copyPasteNewSessionItem;
    private Text prompt;
    private Button sendButton;
    private Button stopButton;
    private Button newSessionButton;
    private Button closeButton;
    private Button sessionsButton;
    private Button applyButton;
    private Button rejectButton;
    private Button undoButton;
    private Button contextButton;
    private Button commandsButton;
    private Button settingsButton;
    private Button attachButton;
    private Combo modelSelector;
    private Combo thoughtLevelSelector;
    private Combo collaborationModeSelector;
    private Label collaborationModeLabel;
    private Label providerLabel;
    private Label sessionNameLabel;
    private Composite collaborationModeBar;
    private Label status;
    private Composite reviewBar;
    private Label reviewSummary;
    private Composite composer;
    private Combo projectSelector;
    private String chatFontFamily = "sans-serif";
    private int chatFontSizePoints = 10;
    private final List<ChatSessionModel> sessions = new ArrayList<>();
    private final AcpSessionService sessionService = new AcpSessionService();
    private final AcpChatDialogs dialogs = new AcpChatDialogs(() -> getSite().getShell(), this::ui);
    private ChatSessionModel activeSession;
    private final ImageRegistry iconRegistry = new ImageRegistry();

    @Override
    public void createPartControl(Composite parent) {
        GridLayout rootLayout = new GridLayout(1, false);
        rootLayout.marginWidth = 8;
        rootLayout.marginHeight = 8;
        rootLayout.verticalSpacing = 8;
        parent.setLayout(rootLayout);

        Composite header = new Composite(parent, SWT.NONE);
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout headerLayout = new GridLayout(2, false);
        headerLayout.marginWidth = 0;
        headerLayout.marginHeight = 0;
        header.setLayout(headerLayout);

        Composite projectInfo = new Composite(header, SWT.NONE);
        projectInfo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout projectInfoLayout = new GridLayout(2, false);
        projectInfoLayout.marginWidth = 0;
        projectInfoLayout.marginHeight = 0;
        projectInfo.setLayout(projectInfoLayout);

        Label projectLabel = new Label(projectInfo, SWT.NONE);
        projectLabel.setText("Project:");
        projectSelector = new Combo(projectInfo, SWT.DROP_DOWN | SWT.READ_ONLY);
        projectSelector.setToolTipText("Projects with an open ACP session");
        projectSelector.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        projectSelector.addListener(SWT.Selection, ignored -> selectProjectFromCombo());

        sessionNameLabel = new Label(projectInfo, SWT.NONE);
        GridData sessionNameData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        sessionNameData.horizontalSpan = 2;
        sessionNameLabel.setLayoutData(sessionNameData);
        sessionNameLabel.setText("Session: —");
        var fontData = projectSelector.getFont().getFontData();
        if (fontData.length > 0) {
            chatFontFamily = fontData[0].getName();
            chatFontSizePoints = Math.max(8, fontData[0].getHeight() - 1);
        }

        Composite sessionMore = new Composite(header, SWT.NONE);
        sessionMore.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        sessionMore.setLayout(new org.eclipse.swt.layout.RowLayout());

        transcript = new Browser(parent, SWT.NONE);
        transcript.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        installTranscriptContextMenu();
        transcript.addLocationListener(new LocationAdapter() {
            @Override public void changing(LocationEvent event) {
                ChatSessionModel session = activeSession;
                if (session != null && WorkspaceFileOpener.open(getSite().getPage(), session.project, event.location)) {
                    event.doit = false;
                }
            }
        });
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

        collaborationModeBar = new Composite(composer, SWT.NONE);
        collaborationModeBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout collaborationModeLayout = new GridLayout(3, false);
        collaborationModeLayout.marginWidth = collaborationModeLayout.marginHeight = 0;
        collaborationModeBar.setLayout(collaborationModeLayout);
        collaborationModeLabel = new Label(collaborationModeBar, SWT.NONE);
        collaborationModeLabel.setText("Session mode:");
        collaborationModeSelector = new Combo(collaborationModeBar, SWT.DROP_DOWN | SWT.READ_ONLY);
        collaborationModeSelector.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        collaborationModeSelector.setToolTipText("Session mode for the active ACP session");
        collaborationModeSelector.setEnabled(false);
        collaborationModeSelector.addListener(SWT.Selection, ignored -> changeConfigOption(collaborationModeSelector,
                AgentConfigOptions.sessionMode(activeSession), "Session mode"));
        providerLabel = new Label(collaborationModeBar, SWT.NONE);
        providerLabel.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));

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
            updateSendButton();
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
        actionsLayout.center = true;
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

        closeButton = new Button(sessionMore, SWT.PUSH);
        closeButton.setToolTipText("Close this chat");
        closeButton.setEnabled(false);
        closeButton.addListener(SWT.Selection, ignored -> closeActiveSession());

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

        newSessionButton = new Button(actions, SWT.PUSH);
        newSessionButton.setToolTipText("New chat in this project");
        newSessionButton.setEnabled(false);
        newSessionButton.addListener(SWT.Selection, ignored -> openNewSessionForActiveProject());

        sessionsButton = new Button(actions, SWT.PUSH);
        sessionsButton.setText("");
        sessionsButton.setToolTipText("Open sessions for this project");
        sessionsButton.setEnabled(false);
        sessionsButton.addListener(SWT.Selection, ignored -> chooseAgentSession());

        modelSelector = new Combo(actions, SWT.DROP_DOWN | SWT.READ_ONLY);
        modelSelector.setToolTipText("Model for the active ACP session");
        modelSelector.setEnabled(false);
        modelSelector.addListener(SWT.Selection, ignored -> changeModel());

        thoughtLevelSelector = new Combo(actions, SWT.DROP_DOWN | SWT.READ_ONLY);
        thoughtLevelSelector.setToolTipText("Reasoning level for the active ACP session");
        thoughtLevelSelector.setEnabled(false);
        thoughtLevelSelector.addListener(SWT.Selection, ignored -> changeConfigOption(thoughtLevelSelector,
                AgentConfigOptions.thoughtLevel(activeSession), "Reasoning level"));

        settingsButton = new Button(actions, SWT.PUSH);
        settingsButton.setText("Options…");
        settingsButton.setToolTipText("Configure agent options");
        settingsButton.setEnabled(false);
        settingsButton.addListener(SWT.Selection, ignored -> editConfigOption());

        // Keep these optional actions at the end of the action list, but disable them for now.
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

    /** Selects a project's open session, or opens its first session for this view lifetime. */
    public void openSessionFor(IProject project, String initialPrompt) {
        if (project == null || !project.exists() || !project.isOpen() || project.getLocation() == null) {
            onError("Cannot open ACP session", new IllegalArgumentException("The selected project is not open"));
            return;
        }
        ChatSessionModel existing = sessionFor(project);
        if (existing != null) {
            // A failed connection remains visible so its diagnostic can be read.  Opening the
            // project again is an explicit retry and must replace that disconnected session.
            if (existing.client == null) {
                closeSession(existing);
            } else {
                selectSession(existing);
                if (initialPrompt != null && !initialPrompt.isBlank()) sendPrompt(existing, initialPrompt);
                return;
            }
        }
        AcpSessionService.SessionConfiguration configuration = sessionService.newSessionConfiguration();
        AgentProvider provider = configuration.provider();
        String agentName = provider.name();

        boolean reviewFileChanges = configuration.reviewFileChanges();
        ChatSessionModel session = new ChatSessionModel(project, projectSessionLabel(project), provider, reviewFileChanges,
                configuration.hideAgentCommands());
        session.initialPrompt = initialPrompt;
        replaceSessionForProject(session);
        setStatus(session, "Connecting to " + agentName + " in " + project.getLocation() + "…"
                + (reviewFileChanges ? " Changes will be reviewed before applying." : " Changes apply immediately."));
        connect(session, provider, null, false, agentName);
    }

    private void connect(ChatSessionModel session, AgentProvider provider, String restoredSessionId,
            boolean restored, String agentName) {
        AgentClient newClient = sessionService.createClient(provider, listenerFor(session), session.reviewFileChanges);
        session.client = newClient;
        CompletableFuture<Void> connection = sessionService.connect(newClient,
                session.project.getLocation().toFile().toPath(), restoredSessionId);
        connection.whenComplete((ignored, error) -> ui(() -> {
            if (session.client != newClient) {
                return; // A newer connection replaced this one.
            }
            if (error != null) {
                showConnectionError(session, "Could not start " + agentName, unwrap(error));
                retire(session);
                return;
            }
            if (restored) {
                // session/load may finish before SWT processes its replayed message updates.
                // Keep accepting them until the user starts the next prompt.
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

    private ChatSessionModel sessionFor(IProject project) {
        if (activeSession != null && activeSession.project.equals(project)) return activeSession;
        for (int index = sessions.size() - 1; index >= 0; index--) {
            ChatSessionModel session = sessions.get(index);
            if (session.project.equals(project)) return session;
        }
        return null;
    }

    private String projectSessionLabel(IProject project) {
        return project.getName();
    }

    /**
     * Replaces this project's open local session. The selector remains a project selector:
     * one item and one open session per project.
     */
    private void replaceSessionForProject(ChatSessionModel replacement) {
        ChatSessionModel previous = sessionFor(replacement.project);
        if (previous == null) {
            sessions.add(replacement);
            projectSelector.add(replacement.label);
            projectSelector.select(projectSelector.getItemCount() - 1);
        } else {
            int index = sessions.indexOf(previous);
            retire(previous);
            sessions.set(index, replacement);
            projectSelector.select(index);
        }
        activeSession = replacement;
        renderTranscript();
        renderStatus();
        renderSessionName();
        updateControls();
    }

    private void selectSession(ChatSessionModel session) {
        activeSession = session;
        int index = sessions.indexOf(session);
        if (index >= 0 && projectSelector != null && !projectSelector.isDisposed()) projectSelector.select(index);
        renderTranscript();
        renderStatus();
        renderSessionName();
        updateControls();
    }

    private void sendPrompt() {
        String text = prompt.getText().trim();
        ChatSessionModel session = activeSession;
        if (text.isEmpty() || session == null || session.client == null || session.agentMessageOpen) {
            return;
        }
        prompt.setText("");
        sendPrompt(session, text);
    }

    private void sendPrompt(ChatSessionModel session, String text) {
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
        ChatSessionModel current = activeSession;
        if (current == null) return;
        AcpSessionService.SessionConfiguration configuration = sessionService.newSessionConfiguration();
        AgentProvider provider = configuration.provider();
        ChatSessionModel session = new ChatSessionModel(current.project, projectSessionLabel(current.project), provider,
                configuration.reviewFileChanges(), configuration.hideAgentCommands());
        replaceSessionForProject(session);
        setStatus(session, "Connecting to " + provider.name() + " in " + current.project.getLocation() + "…");
        connect(session, provider, null, false, provider.name());
    }

    private void installTranscriptContextMenu() {
        transcriptMenu = new Menu(transcript);
        copyPasteNewSessionItem = new MenuItem(transcriptMenu, SWT.PUSH);
        copyPasteNewSessionItem.setText("Copy and Paste in a New Session");
        copyPasteNewSessionItem.addListener(SWT.Selection, ignored -> {
            String selectedText = selectedTranscriptText();
            if (!selectedText.isBlank()) {
                copyAndPasteInNewSession(selectedText);
            }
        });
        transcriptMenu.addListener(SWT.Show, ignored -> {
            String selectedText = selectedTranscriptText();
            copyPasteNewSessionItem.setEnabled(!selectedText.isBlank()
                    && activeSession != null
                    && activeSession.client != null
                    && !activeSession.isBusy());
        });
        transcript.setMenu(transcriptMenu);
    }

    private String selectedTranscriptText() {
        if (transcript == null || transcript.isDisposed()) return "";
        try {
            Object value = transcript.evaluate(
                    "window.getSelection ? window.getSelection().toString() : ''");
            return value == null ? "" : value.toString();
        } catch (RuntimeException exception) {
            AcpLog.warn("Could not read selected transcript text", exception);
            return "";
        }
    }

    private void copyAndPasteInNewSession(String selectedText) {
        ChatSessionModel session = activeSession;
        if (session == null || session.client == null || session.isBusy() || selectedText.isBlank()) return;

        session.sessionTransitioning = true;
        updateControls();
        Path projectDirectory = session.project.getLocation().toFile().toPath();
        session.client.startNewSession(projectDirectory).whenComplete((ignored, error) -> ui(() -> {
            session.sessionTransitioning = false;
            if (error != null) {
                onError(session, "Could not create a new ACP session", unwrap(error));
                updateControls();
                return;
            }
            resetSessionPresentation(session);
            session.sessionName = "New session";
            selectSession(session);
            prompt.setText(selectedText);
            prompt.setSelection(prompt.getText().length());
            prompt.setFocus();
            setStatus(session, "New session ready");
            updateControls();
        }));
    }

    private void resetSessionPresentation(ChatSessionModel session) {
        session.transcriptMarkdown.setLength(0);
        session.pendingAgentText.setLength(0);
        session.toolCalls.clear();
        session.renderedToolDiffs.clear();
        session.attachments.clear();
        session.commands.clear();
        session.changes = new ChangeReviewService(session.project);
        session.agentMessageOpen = false;
        session.sessionTransitioning = false;
        session.acceptingRestoredTranscript = false;
        session.restoredAgentMessageOpen = false;
    }

    private void chooseAgentSession() {
        ChatSessionModel session = activeSession;
        if (session == null || session.client == null) return;
        AgentClient client = session.client;
        sessionService.listSessions(client, session.project.getLocation().toFile().toPath())
                .whenComplete((available, error) -> ui(() -> {
                    if (session.client != client) return;
                    if (error != null) {
                        onError(session, "Could not list agent sessions", unwrap(error));
                        return;
                    }
                    ListDialog dialog = new ListDialog(getSite().getShell());
                    dialog.setTitle("ACP sessions");
                    dialog.setMessage("Select a saved session for " + session.project.getName() + ":");
                    dialog.setContentProvider(ArrayContentProvider.getInstance());
                    dialog.setLabelProvider(new LabelProvider() {
                        @Override public String getText(Object element) {
                            SessionInfo info = (SessionInfo) element;
                            String title = info.title().isBlank() ? info.id() : info.title();
                            return info.updatedAt().isBlank() ? title : title + " — " + info.updatedAt();
                        }
                    });
                    List<SessionInfo> projectSessions = available.stream()
                            .filter(info -> sessionService.belongsToProject(info,
                                    session.project.getLocation().toFile().toPath()))
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

    private void restoreListedSession(ChatSessionModel session, SessionInfo selected) {
        ChatSessionModel restored = new ChatSessionModel(session.project, projectSessionLabel(session.project),
                session.provider, session.reviewFileChanges, session.hideAgentCommands);
        restored.sessionName = selected.title().isBlank() ? selected.id() : selected.title();
        // session/load may replay transcript updates before its response completes.
        restored.acceptingRestoredTranscript = session.client.capabilities().loadSession();
        replaceSessionForProject(restored);
        setStatus(restored, "Restoring session with " + session.provider.name() + "…");
        connect(restored, session.provider, selected.id(), true, session.provider.name());
    }

    private AcpChatSessionListener listenerFor(ChatSessionModel session) {
        return new AcpChatSessionListener(new AcpChatSessionListener.Callbacks() {
            @Override public void agentText(String text) { queueAgentText(session, text); }
            @Override public void firstChunk(long sentAtNanos, long receivedAtNanos) {
                synchronized (session) {
                    session.firstAgentChunkSentAtNanos = sentAtNanos;
                    session.firstAgentChunkReceivedAtNanos = receivedAtNanos;
                }
            }
            @Override public void promptCompleted(long sentAtNanos, long completedAtNanos) {
                ui(() -> {
                    flushQueuedAgentText(session);
                    append(session, "\n> **Timing:** session/prompt completed in "
                            + elapsedMillis(sentAtNanos, completedAtNanos) + " ms\n\n");
                });
            }
            @Override public void userText(String text) { ui(() -> appendRestoredUserText(session, text)); }
            @Override public void status(String value) { setStatus(session, value); }
            @Override public void error(String message, Throwable error) { AcpChatView.this.onError(session, message, error); }
            @Override public CompletableFuture<String> permission(String title, List<PermissionOption> options) {
                return requestPermissionFor(session, title, options);
            }
            @Override public CompletableFuture<String> permission(PermissionRequest request) {
                return requestPermissionFor(session, request);
            }
            @Override public void toolCall(ToolCall toolCall) { ui(() -> updateToolCall(session, toolCall)); }
            @Override public void commands(List<AgentCommand> commands) { ui(() -> updateCommands(session, commands)); }
            @Override public void configOptions(List<ConfigOption> options) { ui(() -> updateConfigOptions(session, options)); }
            @Override public void usage(Usage usage) { ui(() -> updateUsage(session, usage)); }
            @Override public void terminalOutput(String output) { ui(() -> {
                if (!session.hideAgentCommands) appendTerminalOutput(session, output);
            }); }
            @Override public CompletableFuture<String> authentication(List<AuthMethod> methods) {
                return requestAuthenticationFor(session, methods);
            }
            @Override public CompletableFuture<String> elicitation(ElicitationRequest request) {
                return requestElicitationFor(session, request);
            }
            @Override public CompletableFuture<String> readFile(FileReadRequest request) {
                return CompletableFuture.supplyAsync(() -> {
                    try { return session.changes.read(request); }
                    catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
                });
            }
            @Override public CompletableFuture<Void> stageFileWrite(FileWriteRequest request) {
                return CompletableFuture.runAsync(() -> {
                    try {
                        FileDiff diff = session.changes.preview(request.path(), request.content());
                        if (!session.reviewFileChanges) {
                            session.changes.apply(List.of(diff));
                            ui(() -> reportFileWrite(session, "File write applied", diff.path()));
                            return;
                        }
                        ui(() -> {
                            session.changes.stage(diff);
                            reportFileWrite(session, "File write staged", diff.path());
                            updateControls();
                        });
                    } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
                });
            }
        });
    }

    private void updateCommands(ChatSessionModel session, List<AgentCommand> commands) {
        session.commands.clear();
        commands.forEach(command -> session.commands.put(command.name(), command.description()));
        setStatus(session, session.commands.isEmpty() ? "No slash commands" : session.commands.size() + " slash command(s) available");
        updateControls();
    }

    private void updateConfigOptions(ChatSessionModel session, List<ConfigOption> options) {
        options.forEach(option -> session.configOptions.put(option.id(), option));
        updateControls();
    }

    private void updateUsage(ChatSessionModel session, Usage usage) {
        if (!session.hideAgentCommands) append(session, "> **Usage:** " + ChatMessageFormatter.usage(usage) + "\n\n");
        updateControls();
    }

    private void refreshModelSelector() {
        if (modelSelector == null || modelSelector.isDisposed()) return;
        ConfigOption option = AgentConfigOptions.model(activeSession);
        modelSelector.removeAll();
        if (option == null) {
            modelSelector.setEnabled(false);
            modelSelector.setToolTipText("The ACP agent has not advertised a model selector");
            return;
        }
        String current = AgentConfigOptions.text(option.value());
        int selected = -1;
        for (ConfigOption.Choice choice : option.choices()) {
            modelSelector.add(choice.label());
            if (choice.value().equals(current)) selected = modelSelector.getItemCount() - 1;
        }
        if (selected >= 0) modelSelector.select(selected);
        else if (modelSelector.getItemCount() > 0) modelSelector.select(0);
        modelSelector.setEnabled(activeSession != null && activeSession.client != null && !activeSession.agentMessageOpen);
        modelSelector.setToolTipText(option.description().isBlank() ? "Model for the active ACP session" : option.description());
    }

    private void refreshThoughtLevelSelector() {
        refreshConfigSelector(thoughtLevelSelector, AgentConfigOptions.thoughtLevel(activeSession), "Reasoning level for the active ACP session");
    }

    private void refreshCollaborationModeSelector() {
        refreshConfigSelector(collaborationModeSelector, AgentConfigOptions.sessionMode(activeSession),
                "Session mode for the active ACP session");
    }

    private void refreshProviderLabel() {
        if (providerLabel == null || providerLabel.isDisposed()) return;
        providerLabel.setText(activeSession == null ? "" : "Agent : " + activeSession.provider.name());
    }

    private void refreshConfigSelector(Combo selector, ConfigOption option, String defaultTooltip) {
        if (selector == null || selector.isDisposed()) return;
        selector.removeAll();
        if (option == null) { selector.setEnabled(false); return; }
        String current = AgentConfigOptions.text(option.value());
        for (ConfigOption.Choice choice : option.choices()) {
            selector.add(choice.label());
            if (choice.value().equals(current)) selector.select(selector.getItemCount() - 1);
        }
        selector.setEnabled(activeSession != null && activeSession.client != null && !activeSession.agentMessageOpen);
        selector.setToolTipText(option.description().isBlank() ? defaultTooltip : option.description());
    }

    private void changeModel() {
        ChatSessionModel session = activeSession;
        ConfigOption option = AgentConfigOptions.model(session);
        int selected = modelSelector == null ? -1 : modelSelector.getSelectionIndex();
        if (session == null || session.client == null || option == null || selected < 0 || selected >= option.choices().size()) return;
        ConfigOption.Choice choice = option.choices().get(selected);
        modelSelector.setEnabled(false);
        session.client.setConfigOption(option.id(), ConfigValue.of(choice.value())).whenComplete((ignored, error) -> ui(() -> {
            if (session.client == null || session != activeSession) return;
            if (error != null) {
                refreshModelSelector();
                onError(session, "Could not change model", unwrap(error));
            } else {
                session.configOptions.put(option.id(), new ConfigOption(option.id(), option.name(), option.description(),
                        option.category(), ConfigValue.of(choice.value()), option.choices()));
                setStatus(session, "Model changed to " + choice.label());
                updateControls();
            }
        }));
    }

    private void changeConfigOption(Combo selector, ConfigOption option, String optionName) {
        ChatSessionModel session = activeSession;
        int selected = selector == null ? -1 : selector.getSelectionIndex();
        if (session == null || session.client == null || option == null || selected < 0 || selected >= option.choices().size()) return;
        ConfigOption.Choice choice = option.choices().get(selected);
        selector.setEnabled(false);
        session.client.setConfigOption(option.id(), ConfigValue.of(choice.value())).whenComplete((ignored, error) -> ui(() -> {
            if (session.client == null || session != activeSession) return;
            if (error != null) {
                refreshConfigSelector(selector, option, optionName + " for the active ACP session");
                onError(session, "Could not change " + optionName.toLowerCase(), unwrap(error));
            } else {
                session.configOptions.put(option.id(), new ConfigOption(option.id(), option.name(), option.description(),
                        option.category(), ConfigValue.of(choice.value()), option.choices()));
                setStatus(session, optionName + " changed to " + choice.label());
                updateControls();
            }
        }));
    }

    private void appendTerminalOutput(ChatSessionModel session, String output) {
        String markdown = ChatMessageFormatter.terminalOutput(output);
        if (!markdown.isEmpty()) append(session, markdown);
    }

    private void chooseCommand() {
        ChatSessionModel session = activeSession;
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
        ChatSessionModel session = activeSession;
        if (session == null || session.configOptions.isEmpty() || session.client == null) return;
        ConfigOptionsDialog dialog = new ConfigOptionsDialog(getSite().getShell(), new ArrayList<>(session.configOptions.values()));
        if (dialog.open() != org.eclipse.jface.window.Window.OK) return;
        dialog.changedValues().forEach((option, value) -> session.client.setConfigOption(option.id(), ConfigValue.of(value)).whenComplete((ignored, error) -> ui(() -> {
            if (session.client == null) return;
            if (error != null) {
                onError(session, "Could not update " + option.name(), unwrap(error));
            } else {
                session.configOptions.put(option.id(), new ConfigOption(option.id(), option.name(), option.description(),
                        option.category(), ConfigValue.of(value), option.choices()));
                setStatus(session, "Updated " + option.name());
                updateControls();
            }
        })));
    }

    private void attachFile() {
        ChatSessionModel session = activeSession; if (session == null) return;
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

    private CompletableFuture<String> requestElicitationFor(ChatSessionModel session, ElicitationRequest request) {
        return dialogs.requestElicitation(sessionLabel(session), request);
    }

    public void onAgentText(String text) {
        ui(() -> appendAgentText(activeSession, text));
    }

    public void onUserText(String text) {
        ui(() -> appendRestoredUserText(activeSession, text));
    }

    public void onStatus(String value) {
        setStatus(activeSession, value);
    }

    public void onError(String message, Throwable error) {
        onError(activeSession, message, error);
    }

    private void setStatus(ChatSessionModel session, String value) {
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

    private void renderSessionName() {
        if (sessionNameLabel == null || sessionNameLabel.isDisposed()) return;
        String name = activeSession == null || activeSession.sessionName == null
                || activeSession.sessionName.isBlank() ? "—" : activeSession.sessionName;
        sessionNameLabel.setText("Session: " + name);
        sessionNameLabel.getParent().layout();
    }

    private void onError(ChatSessionModel session, String message, Throwable error) {
        ui(() -> {
            if (session == null) return;
            showConnectionError(session, message, error);
        });
    }

    /** Renders immediately when the caller is already on the SWT UI thread. */
    private void showConnectionError(ChatSessionModel session, String message, Throwable error) {
        String detail = error == null || error.getMessage() == null ? "" : ": " + error.getMessage();
        append(session, "\n> **Error:** " + message + detail + "\n\n");
        setStatus(session, "Error");
        updateControls();
    }

    public CompletableFuture<String> requestPermission(String title, List<PermissionOption> options) {
        return requestPermissionFor(activeSession, title, options);
    }

    private CompletableFuture<String> requestAuthenticationFor(ChatSessionModel session, List<AuthMethod> methods) {
        return dialogs.requestAuthentication(sessionLabel(session), methods);
    }

    private CompletableFuture<String> requestPermissionFor(ChatSessionModel session, String title, List<PermissionOption> options) {
        return dialogs.requestPermission(sessionLabel(session), title, options);
    }

    private CompletableFuture<String> requestPermissionFor(ChatSessionModel session, PermissionRequest request) {
        return dialogs.requestPermission(sessionLabel(session), request);
    }

    private static String sessionLabel(ChatSessionModel session) {
        return session == null ? "ACP session" : session.label;
    }

    private void updateToolCall(ChatSessionModel session, ToolCall toolCall) {
        session.toolCalls.put(toolCall.id(), toolCall);
        if (session.reviewFileChanges) session.changes.stageAll(toolCall.diffs());
        if (session.hideAgentCommands) {
            setStatus(session, toolCallStatus(toolCall));
        } else {
            appendNewToolDiffs(session, toolCall);
            append(session, "\n> **Tool " + toolCall.kind() + ":** " + toolCall.title() + " — " + toolCall.status()
                    + (toolCall.hasDiffs() ? " (" + toolCall.diffs().size() + " file change(s) ready for review)" : "")
                    + "\n\n");
            if (toolCall.kind().toLowerCase(java.util.Locale.ROOT).contains("terminal"))
                appendTerminalOutput(session, toolCall.terminalOutput());
        }
        updateControls();
        if (!session.reviewFileChanges && toolCall.hasDiffs()) applyImmediately(session, toolCall.diffs());
    }

    private static String toolCallStatus(ToolCall toolCall) {
        String title = toolCall.title() == null || toolCall.title().isBlank() ? toolCall.kind() : toolCall.title();
        String status = toolCall.status() == null || toolCall.status().isBlank() ? "updated" : toolCall.status();
        return "Agent command: " + title + " — " + status;
    }

    private void reportFileWrite(ChatSessionModel session, String action, String path) {
        if (session.hideAgentCommands) {
            setStatus(session, action + ": " + path);
        } else {
            append(session, "> **" + action + ":** `" + path + "`\n\n");
        }
    }

    private void appendNewToolDiffs(ChatSessionModel session, ToolCall toolCall) {
        if (toolCall.diffs().isEmpty() || toolCall.diffs().equals(session.renderedToolDiffs.get(toolCall.id()))) return;
        session.renderedToolDiffs.put(toolCall.id(), List.copyOf(toolCall.diffs()));
        append(session, "\n> **File changes:** " + toolCall.diffs().size() + " file change(s)\n"
                + ChatMessageFormatter.diffPreview(toolCall.diffs()) + "\n\n");
    }

    private void applyImmediately(ChatSessionModel session, List<FileDiff> diffs) {
        CompletableFuture.runAsync(() -> {
            try {
                session.changes.apply(diffs);
                ui(() -> setStatus(session, "Changes applied"));
            } catch (Exception error) { onError(session, "Could not apply agent changes", error); }
        });
    }

    private List<FileDiff> pendingDiffs(ChatSessionModel session) {
        if (session == null) return List.of();
        return session.changes.pending();
    }

    private void applyChanges() {
        ChatSessionModel session = activeSession;
        if (session == null) return;
        List<FileDiff> diffs = pendingDiffs(session);
        CompletableFuture.runAsync(() -> {
            try {
                int count = session.changes.apply(diffs);
                ui(() -> {
                    session.changes.clear();
                    append(session, "> Applied " + count + " reviewed file change(s).\n\n");
                    setStatus(session, "Changes applied");
                    updateControls();
                });
            } catch (Exception error) { onError(session, "Could not apply reviewed changes", error); }
        });
    }

    private void rejectChanges() {
        ChatSessionModel session = activeSession;
        if (session == null) return;
        List<FileDiff> diffs = pendingDiffs(session);
        CompletableFuture.runAsync(() -> {
            try {
                int reverted = session.changes.reject(diffs);
                ui(() -> {
                    session.changes.clear();
                    append(session, "> Rejected " + diffs.size() + " reviewed file change(s)"
                            + (reverted == 0 ? "." : " and reverted " + reverted + " direct write(s).") + "\n\n");
                    setStatus(session, "Changes rejected");
                    updateControls();
                });
            } catch (Exception error) { onError(session, "Could not reject reviewed changes", error); }
        });
    }

    private void undoApply() {
        ChatSessionModel session = activeSession;
        if (session == null) return;
        CompletableFuture.runAsync(() -> {
            try {
                int count = session.changes.undo();
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

    private void append(ChatSessionModel session, String text) {
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

    private void appendRestoredUserText(ChatSessionModel session, String text) {
        if (session == null || !session.acceptingRestoredTranscript || text.isEmpty()) return;
        append(session, "## You\n\n" + text + "\n\n");
        session.restoredAgentMessageOpen = false;
    }

    private void appendAgentText(ChatSessionModel session, String text) {
        if (session == null || text.isEmpty()) return;
        if (session.acceptingRestoredTranscript && !session.restoredAgentMessageOpen) {
            append(session, "## Agent\n\n");
            session.restoredAgentMessageOpen = true;
        }
        append(session, text);
    }

    /** Coalesces high-frequency ACP chunks so the SWT/WebView queue cannot grow unbounded. */
    private void queueAgentText(ChatSessionModel session, String text) {
        if (session == null || text == null || text.isEmpty()) return;
        synchronized (session) {
            session.pendingAgentText.append(text);
            if (session.agentRenderScheduled) return;
            session.agentRenderScheduled = true;
        }
        ui(() -> getSite().getShell().getDisplay().timerExec(40, () -> flushQueuedAgentText(session)));
    }

    /** Runs on the SWT thread, either after the batching interval or at prompt completion. */
    private void flushQueuedAgentText(ChatSessionModel session) {
        String text;
        long sentAt;
        long receivedAt;
        synchronized (session) {
            text = session.pendingAgentText.toString();
            session.pendingAgentText.setLength(0);
            session.agentRenderScheduled = false;
            sentAt = session.firstAgentChunkSentAtNanos;
            receivedAt = session.firstAgentChunkReceivedAtNanos;
            session.firstAgentChunkSentAtNanos = 0;
            session.firstAgentChunkReceivedAtNanos = 0;
        }
        if (!text.isEmpty()) appendAgentText(session, text);
        if (receivedAt != 0) {
            long uiAt = System.nanoTime();
            AcpLog.info("ACP first agent chunk rendered on SWT UI thread: session='" + session.label
                    + "', receiveToUiMs=" + elapsedMillis(receivedAt, uiAt)
                    + ", sendToUiMs=" + elapsedMillis(sentAt, uiAt));
        }
    }

    private void renderTranscript() {
        if (transcript == null || transcript.isDisposed()) return;
        transcript.setText(chatDocument(activeSession == null ? "" : activeSession.transcriptMarkdown.toString()));
        scrollTranscriptToBottom();
    }

    private String chatDocument(String markdown) {
        return GfmRenderer.document(markdown, chatFontFamily, chatFontSizePoints,
                activeSession == null ? null : activeSession.fileLinks::hrefFor);
    }

    private org.eclipse.swt.graphics.Image lucideIcon(String name) {
        String key = "lucide-" + name;
        if (iconRegistry.getDescriptor(key) == null) {
            iconRegistry.put(key, ImageDescriptor.createFromFile(AcpChatView.class, "/icons/" + key + ".png"));
        }
        return iconRegistry.get(key);
    }

    private void applyButtonImages() {
        ISharedImages images = PlatformUI.getWorkbench().getSharedImages();
        sendButton.setImage(lucideIcon("send-horizontal"));
        stopButton.setImage(images.getImage(ISharedImages.IMG_ELCL_STOP));
        newSessionButton.setImage(lucideIcon("message-square-plus"));
        closeButton.setImage(lucideIcon("x"));
        sessionsButton.setImage(lucideIcon("list-clock"));
        applyButton.setImage(images.getImage(ISharedImages.IMG_ETOOL_SAVE_EDIT));
        rejectButton.setImage(images.getImage(ISharedImages.IMG_ETOOL_DELETE));
        undoButton.setImage(images.getImage(ISharedImages.IMG_TOOL_UNDO));
        contextButton.setImage(lucideIcon("circle-fading-plus"));
        commandsButton.setImage(lucideIcon("square-slash"));
        settingsButton.setImage(lucideIcon("circle-ellipsis"));
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
        boolean connected = activeSession != null && activeSession.isConnected();
        updateSendButton();
        boolean busy = activeSession != null && activeSession.isBusy();
        showControl(sendButton, !busy);
        showControl(stopButton, busy);
        stopButton.setEnabled(connected && activeSession.agentMessageOpen);
        newSessionButton.setEnabled(connected && activeSession.project.isOpen());
        closeButton.setEnabled(connected);
        sessionsButton.setEnabled(connected && activeSession.client.capabilities().sessionList()
                && (activeSession.client.capabilities().sessionResume() || activeSession.client.capabilities().loadSession()));
        boolean reviewFileChanges = connected && activeSession.reviewFileChanges;
        boolean hasDiffs = reviewFileChanges && !pendingDiffs(activeSession).isEmpty();
        applyButton.setEnabled(hasDiffs);
        rejectButton.setEnabled(hasDiffs);
        undoButton.setEnabled(reviewFileChanges && activeSession.changes.canUndo());
        contextButton.setEnabled(false);
        commandsButton.setEnabled(false);
        settingsButton.setEnabled(connected && !activeSession.configOptions.isEmpty());
        attachButton.setEnabled(false);
        refreshModelSelector();
        refreshThoughtLevelSelector();
        refreshCollaborationModeSelector();
        refreshProviderLabel();
        showControl(settingsButton, settingsButton.getEnabled());
        // Keep selectors visible while a turn is in progress; refresh disables them until idle.
        showControl(modelSelector, modelSelector.getItemCount() > 0);
        showControl(thoughtLevelSelector, thoughtLevelSelector.getItemCount() > 0);
        boolean sessionModeAvailable = collaborationModeSelector.getItemCount() > 0;
        showControl(collaborationModeLabel, sessionModeAvailable);
        showControl(collaborationModeSelector, sessionModeAvailable);
        showControl(collaborationModeBar, activeSession != null);
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

    /**
     * The prompt modify listener runs for every keystroke. Keep that path limited to
     * the one control whose state depends on its content: refreshing the whole
     * composer rebuilds configuration combos and forces a costly SWT layout.
     */
    private void updateSendButton() {
        if (sendButton == null || sendButton.isDisposed() || prompt == null || prompt.isDisposed()) return;
        boolean enabled = activeSession != null && activeSession.isConnected()
                && !activeSession.agentMessageOpen && !prompt.getText().isBlank();
        if (sendButton.getEnabled() != enabled) sendButton.setEnabled(enabled);
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

    private void closeSession(ChatSessionModel session) {
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
            renderSessionName();
        }
        updateControls();
    }

    /** session/close gives agents a chance to durably store the conversation. */
    private void retire(ChatSessionModel session) {
        AgentClient previousClient = session.client;
        session.client = null;
        if (previousClient != null) CompletableFuture.runAsync(previousClient::close);
    }

    private void disconnect() {
        for (ChatSessionModel session : new ArrayList<>(sessions)) {
            AgentClient previousClient = session.client;
            session.client = null;
            if (previousClient != null) CompletableFuture.runAsync(previousClient::close);
        }
        sessions.clear();
        activeSession = null;
        renderStatus();
        renderSessionName();
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
        if (sessionsButton != null && !sessionsButton.isDisposed()) sessionsButton.setEnabled(false);
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

    private static long elapsedMillis(long startedAt, long completedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(completedAt - startedAt);
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
        iconRegistry.dispose();
        super.dispose();
    }
}
