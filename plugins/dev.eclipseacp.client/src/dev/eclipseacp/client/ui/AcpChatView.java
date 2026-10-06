package dev.eclipseacp.client.ui;

import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.ImageRegistry;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.part.ViewPart;

import dev.eclipseacp.client.acp.AcpClientFactory;
import dev.eclipseacp.client.preferences.AgentProviderRegistry;
import dev.eclipseacp.client.preferences.AcpPreferences;

/** Composes the chat controls and presents the selected project's session. */
public final class AcpChatView extends ViewPart {
    public static final String ID = "dev.eclipseacp.client.views.chat";
    private ChatTranscript transcript;
    private ChatComposer composer;
    private RecentSessionsPanel recentSessions;
    private ChatPageNavigator navigator;
    private IconButton backButton;
    private Label headerTitle;
    private Combo projectSelector;
    private IconButton closeProjectButton;
    private IconButton newSessionButton;
    private IconButton optionsButton;
    private Label status;
    private ChatSessionModel activeSession;
    private final ImageRegistry iconRegistry = new ImageRegistry();
    private final AcpChatDialogs dialogs = new AcpChatDialogs(() -> getSite().getShell(), this::ui);
    private final AcpSessionService sessionService = new AcpSessionService(() -> {
        // Resolve current preferences for each new chat while the view remains open.
        var preferences = AcpPreferences.store();
        return new AcpSessionService.SessionConfiguration(new AgentProviderRegistry(preferences).active(),
                preferences.getBoolean(AcpPreferences.HIDE_AGENT_COMMANDS_IN_CHAT));
    }, this::ui,
            new AcpSessionService.Presentation() {
                @Override public void selected(ChatSessionModel session) { selectSession(session); }
                @Override public void changed(ChatSessionModel session) {
                    if (session == activeSession) updateControls();
                }
                @Override public void statusChanged(ChatSessionModel session) {
                    if (session == activeSession) renderStatus();
                }
                @Override public void transcriptChanged(ChatSessionModel session) {
                    if (session == activeSession) transcript.update();
                }
                @Override public void transcriptReloaded(ChatSessionModel session) {
                    if (session == activeSession) transcript.render();
                }
                @Override public void inputReady(ChatSessionModel session, String text) {
                    composer.prepareInput(session, text);
                }
                @Override public void draftReady(ChatSessionModel session, String text) {
                    composer.restoreDraft(session, text);
                }
            }, this::listenerFor,
            (session, listener) -> AcpClientFactory.create(session.provider, listener, List.of()),
            new SessionOperationScope());

    @Override
    public void createPartControl(Composite parent) {
        GridLayout rootLayout = new GridLayout(1, false);
        rootLayout.marginWidth = 8;
        rootLayout.marginHeight = 8;
        rootLayout.verticalSpacing = 8;
        parent.setLayout(rootLayout);

        Composite projectBar = new Composite(parent, SWT.NONE);
        projectBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout projectLayout = new GridLayout(2, false);
        projectLayout.marginWidth = projectLayout.marginHeight = 0;
        projectLayout.horizontalSpacing = 4;
        projectBar.setLayout(projectLayout);

        projectSelector = new Combo(projectBar, SWT.DROP_DOWN | SWT.READ_ONLY);
        projectSelector.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        projectSelector.setToolTipText("Current project");
        projectSelector.addListener(SWT.Selection, ignored -> selectProjectFromCombo());
        closeProjectButton = new IconButton(projectBar, lucideIcon("x"),
                "Close the current session and remove the project from the list", this::closeCurrentProject);
        closeProjectButton.setEnabled(false);

        Composite header = new Composite(parent, SWT.NONE);
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout headerLayout = new GridLayout(4, false);
        headerLayout.marginWidth = headerLayout.marginHeight = 0;
        header.setLayout(headerLayout);

        backButton = new IconButton(header, lucideIcon("arrow-left"), "Back to chats", () -> {
            updateRecentSessions();
            navigator.show(false, true);
        });
        showControl(backButton, false);

        headerTitle = new Label(header, SWT.NONE);
        headerTitle.setText("Chats");
        headerTitle.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));
        headerTitle.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        optionsButton = new IconButton(header, lucideIcon("settings"), "Agent options",
                () -> composer.editConfigOption());

        newSessionButton = new IconButton(header, lucideIcon("square-pen"),
                "New chat in this project", () -> {
                    navigator.show(true, true);
                    sessionService.newSession(null);
                });
        newSessionButton.setEnabled(false);

        Composite pageHost = new Composite(parent, SWT.DOUBLE_BUFFERED);
        pageHost.setBackground(parent.getBackground());
        pageHost.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        recentSessions = new RecentSessionsPanel(pageHost,
                AcpPreferences.visibleRecentSessions(AcpPreferences.store()), this::openRecent, session -> {
                    sessionService.close(session);
                    updateRecentSessions();
                });

        var fontData = headerTitle.getFont().getFontData();
        String fontFamily = fontData.length == 0 ? "sans-serif" : fontData[0].getName();
        int fontSize = fontData.length == 0 ? 10 : Math.max(8, fontData[0].getHeight() - 1);
        Composite chatPage = new Composite(pageHost, SWT.NONE);
        GridLayout chatLayout = new GridLayout(1, false);
        chatLayout.marginWidth = chatLayout.marginHeight = 0;
        chatPage.setLayout(chatLayout);
        transcript = new ChatTranscript(chatPage, getSite().getPage(), () -> activeSession,
                sessionService::newSession, fontFamily, fontSize);
        navigator = new ChatPageNavigator(pageHost, recentSessions.control(), chatPage, showChat -> {
            updateHeaderTitle();
            if (showChat) setBackVisible(true);
        }, this::setBackVisible);

        composer = new ChatComposer(parent, JFaceResources.getFontRegistry().get(JFaceResources.DEFAULT_FONT), getSite().getPage(),
                sessionService, dialogs, navigator::isChatVisible, this::sendInNewSession, this::lucideIcon);

        status = new Label(parent, SWT.NONE);
        status.setText("Not connected");
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        updateControls();
    }

    /** Called by the project/resource context-menu command. */
    public void openSessionFor(IProject project) {
        openSessionFor(project, null);
    }

    public void openSessionFor(IProject project, String initialPrompt) {
        sessionService.openSessionFor(project, initialPrompt);
        ChatSessionModel selected = sessionService.activeSession();
        if (selected != null && selected.project.equals(project)) navigator.show(true, true);
    }

    private void selectSession(ChatSessionModel session) {
        activeSession = session;
        refreshProjectSelector();
        transcript.render();
        updateControls();
    }

    private void sendInNewSession(String prompt) {
        navigator.show(true, true);
        sessionService.newSessionWithInitialPrompt(prompt);
    }

    private void selectProjectFromCombo() {
        int index = projectSelector.getSelectionIndex();
        List<ChatSessionModel> sessions = sessionService.sessions();
        if (index >= 0 && index < sessions.size()) sessionService.select(sessions.get(index));
    }

    private void closeCurrentProject() {
        ChatSessionModel session = activeSession;
        if (session == null) return;
        boolean lastProject = sessionService.sessions().size() == 1;
        sessionService.close(session);
        if (lastProject) navigator.show(false, false);
    }

    private void refreshProjectSelector() {
        if (projectSelector == null || projectSelector.isDisposed()) return;
        List<ChatSessionModel> sessions = sessionService.sessions();
        projectSelector.setItems(sessions.stream().map(session -> session.label).toArray(String[]::new));
        int selected = activeSession == null ? -1 : sessions.indexOf(activeSession);
        if (selected >= 0) projectSelector.select(selected);
        else projectSelector.deselectAll();
        projectSelector.setEnabled(!sessions.isEmpty());
    }

    private AcpChatSessionListener listenerFor(ChatSessionModel session) {
        return new AcpChatSessionListener(session, sessionService, dialogs, this::ui,
                action -> getSite().getShell().getDisplay().timerExec(40, action));
    }

    private void updateRecentSessions() {
        recentSessions.update(activeSession);
    }

    private void openRecent(RecentSessionsPanel.RecentSession entry) {
        if (entry.open() != null) {
            navigator.show(true, true);
            sessionService.select(entry.open());
        } else if (entry.saved() != null && activeSession != null && !activeSession.isBusy()) {
            navigator.show(true, true);
            sessionService.restore(entry.saved());
        }
    }

    private void setBackVisible(boolean visible) {
        if (backButton == null || backButton.isDisposed()) return;
        showControl(backButton, visible);
        backButton.getParent().layout(true, true);
    }

    private void updateControls() {
        if (status == null || status.isDisposed()) return;
        updateHeaderTitle();
        boolean connected = activeSession != null && activeSession.isConnected();
        boolean busy = activeSession != null && activeSession.isBusy();
        backButton.setEnabled(activeSession != null);
        newSessionButton.setEnabled(connected && !busy && activeSession.project.isOpen());
        closeProjectButton.setEnabled(activeSession != null);
        updateRecentSessions();
        renderStatus();
        composer.update();
        optionsButton.setEnabled(composer.canEditConfigOption());
    }

    private void updateHeaderTitle() {
        if (headerTitle == null || headerTitle.isDisposed()) return;
        String text = "Chats";
        if (navigator.isChatVisible() && activeSession != null && activeSession.sessionName != null
                && !activeSession.sessionName.isBlank()) {
            text = activeSession.sessionName;
        }
        if (!text.equals(headerTitle.getText())) {
            headerTitle.setText(text);
            headerTitle.getParent().layout(true, true);
        }
    }

    private void renderStatus() {
        if (status == null || status.isDisposed()) return;
        status.setText(activeSession == null ? "Not connected" : activeSession.statusText);
        status.getParent().layout();
    }

    private static void showControl(Control control, boolean visible) {
        control.setVisible(visible);
        GridData data = control.getLayoutData() instanceof GridData existing ? existing : new GridData();
        data.exclude = !visible;
        control.setLayoutData(data);
    }

    private org.eclipse.swt.graphics.Image lucideIcon(String name) {
        String key = "lucide-" + name;
        if (iconRegistry.getDescriptor(key) == null) {
            iconRegistry.put(key, ImageDescriptor.createFromFile(AcpChatView.class, "/icons/" + key + ".png"));
        }
        return iconRegistry.get(key);
    }

    private void ui(Runnable action) {
        Display display = getSite().getShell().getDisplay();
        if (display.isDisposed()) return;
        display.asyncExec(() -> {
            if (transcript != null && !transcript.isDisposed()) action.run();
        });
    }

    @Override public void setFocus() {
        composer.setFocus();
    }

    @Override public void dispose() {
        sessionService.disconnect();
        if (transcript != null) transcript.dispose();
        iconRegistry.dispose();
        super.dispose();
    }
}
