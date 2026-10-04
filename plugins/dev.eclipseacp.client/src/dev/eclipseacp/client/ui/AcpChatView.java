package dev.eclipseacp.client.ui;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.ImageRegistry;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleControlAdapter;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.part.ViewPart;

import dev.eclipseacp.client.agent.SessionInfo;
import dev.eclipseacp.client.preferences.AcpPreferences;

/** Composes the chat controls and presents the selected project's session. */
public final class AcpChatView extends ViewPart {
    public static final String ID = "dev.eclipseacp.client.views.chat";
    private ChatTranscript transcript;
    private ChatComposer composer;
    private Composite pageHost;
    private Composite sessionsPage;
    private Composite chatPage;
    private IconButton backButton;
    private Label headerTitle;
    private Combo projectSelector;
    private IconButton closeProjectButton;
    private IconButton newSessionButton;
    private IconButton optionsButton;
    private List<Canvas> recentButtons = List.of();
    private Canvas viewAllButton;
    private List<RecentSession> recentSessions = List.of();
    private Label status;
    private ChatSessionModel activeSession;
    private boolean chatPageVisible;
    private int pageAnimationGeneration;
    private final ImageRegistry iconRegistry = new ImageRegistry();
    private record RecentSession(String label, String updatedAt, ChatSessionModel open, SessionInfo saved) { }
    private final AcpChatDialogs dialogs = new AcpChatDialogs(() -> getSite().getShell(), this::ui);
    private final AcpSessionService sessionService = new AcpSessionService(this::ui,
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
                @Override public void inputReady(ChatSessionModel session, String text) {
                    composer.prepareInput(session, text);
                }
            }, this::listenerFor);

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
            showChatPage(false, true);
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
                    showChatPage(true, true);
                    sessionService.newSession(null);
                });
        newSessionButton.setEnabled(false);

        pageHost = new Composite(parent, SWT.DOUBLE_BUFFERED);
        pageHost.setBackground(parent.getBackground());
        pageHost.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        pageHost.addListener(SWT.Resize, ignored -> layoutPages());

        sessionsPage = new Composite(pageHost, SWT.NONE);
        sessionsPage.setBackground(parent.getBackground());
        GridLayout recentLayout = new GridLayout(1, false);
        recentLayout.marginWidth = recentLayout.marginHeight = 0;
        recentLayout.verticalSpacing = 1;
        sessionsPage.setLayout(recentLayout);
        List<Canvas> buttons = new ArrayList<>();
        for (int i = 0; i < AcpPreferences.visibleRecentSessions(AcpPreferences.store()); i++) {
            final int index = i;
            Canvas row = createTextAction(sessionsPage,
                    () -> index < recentSessions.size() ? recentSessions.get(index).label() : "",
                    () -> index < recentSessions.size()
                            ? SessionAge.format(recentSessions.get(index).updatedAt(), Instant.now()) : "",
                    () -> openRecent(index));
            Menu menu = new Menu(row);
            MenuItem close = new MenuItem(menu, SWT.PUSH);
            close.setText("Close chat");
            close.addListener(SWT.Selection, ignored -> {
                if (index < recentSessions.size() && recentSessions.get(index).open() != null) {
                    sessionService.close(recentSessions.get(index).open());
                    updateRecentSessions();
                }
            });
            menu.addListener(SWT.Show, ignored -> close.setEnabled(index < recentSessions.size()
                    && recentSessions.get(index).open() != null));
            row.setMenu(menu);
            buttons.add(row);
        }
        recentButtons = List.copyOf(buttons);
        viewAllButton = createTextAction(sessionsPage, () -> "View all (" + recentSessions.size() + ")", () -> "",
                this::showAllSessions);
        viewAllButton.setToolTipText("Show all available chats");

        var fontData = headerTitle.getFont().getFontData();
        String fontFamily = fontData.length == 0 ? "sans-serif" : fontData[0].getName();
        int fontSize = fontData.length == 0 ? 10 : Math.max(8, fontData[0].getHeight() - 1);
        chatPage = new Composite(pageHost, SWT.NONE);
        GridLayout chatLayout = new GridLayout(1, false);
        chatLayout.marginWidth = chatLayout.marginHeight = 0;
        chatPage.setLayout(chatLayout);
        transcript = new ChatTranscript(chatPage, getSite().getPage(), () -> activeSession,
                sessionService::newSession, fontFamily, fontSize);
        chatPage.setVisible(false);

        composer = new ChatComposer(parent, JFaceResources.getFontRegistry().get(JFaceResources.DEFAULT_FONT), getSite().getPage(),
                sessionService, dialogs, () -> chatPageVisible, this::sendInNewSession, this::lucideIcon);

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
        if (selected != null && selected.project.equals(project)) showChatPage(true, true);
    }

    private void selectSession(ChatSessionModel session) {
        activeSession = session;
        refreshProjectSelector();
        transcript.render();
        updateControls();
    }

    private void sendInNewSession(String prompt) {
        showChatPage(true, true);
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
        if (lastProject) showChatPage(false, false);
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
        List<RecentSession> entries = new ArrayList<>();
        if (activeSession != null) {
            List<SessionInfo> saved = activeSession.savedSessions.stream()
                    .sorted(Comparator.comparing((SessionInfo info) -> info.updatedAt() == null ? "" : info.updatedAt()).reversed())
                    .toList();
            boolean activeIsSaved = activeSession.sessionId != null
                    && saved.stream().anyMatch(info -> activeSession.sessionId.equals(info.id()));
            if (!activeIsSaved && !"New session".equals(activeSession.sessionName)) {
                entries.add(new RecentSession(chatTitle(activeSession.sessionName), null, activeSession, null));
            }
            for (SessionInfo info : saved) {
                boolean active = info.id().equals(activeSession.sessionId);
                if (active && info.title().isBlank()) continue;
                entries.add(new RecentSession(chatTitle(info.title().isBlank() ? info.id() : info.title()), info.updatedAt(),
                        active && activeSession.isConnected() ? activeSession : null,
                        active && activeSession.isConnected() ? null : info));
            }
        }
        recentSessions = List.copyOf(entries);
        boolean canRestore = activeSession != null && !activeSession.isBusy()
                && activeSession.canRestoreSavedSessions();
        for (int i = 0; i < recentButtons.size(); i++) {
            boolean visible = i < entries.size();
            Canvas button = recentButtons.get(i);
            button.setEnabled(false);
            if (visible) {
                RecentSession entry = entries.get(i);
                button.setEnabled(entry.open() != null || canRestore);
                button.setToolTipText((entry.open() == null ? "Restore chat: " : "Open chat: ") + entry.label());
            }
            showControl(button, visible);
            button.redraw();
        }
        viewAllButton.setEnabled(!entries.isEmpty());
        showControl(viewAllButton, !entries.isEmpty());
        viewAllButton.redraw();
        viewAllButton.getParent().layout(true, true);
    }

    private Canvas createTextAction(Composite parent, Supplier<String> label, Supplier<String> trailingLabel,
            Runnable action) {
        Canvas control = new Canvas(parent, SWT.DOUBLE_BUFFERED);
        control.setBackground(parent.getBackground());
        control.setForeground(parent.getDisplay().getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
        control.setCursor(parent.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
        data.heightHint = 18;
        control.setLayoutData(data);
        control.addPaintListener(event -> {
            String text = label.get();
            String trailingText = trailingLabel.get();
            event.gc.setForeground(control.getEnabled() ? control.getForeground()
                    : control.getDisplay().getSystemColor(SWT.COLOR_WIDGET_DISABLED_FOREGROUND));
            int trailingWidth = trailingText.isEmpty() ? 0 : event.gc.textExtent(trailingText).x;
            int trailingGap = trailingText.isEmpty() ? 0 : 10;
            int availableWidth = Math.max(0, control.getClientArea().width - 10 - trailingWidth - trailingGap);
            String visibleText = ellipsize(event.gc, chatTitle(text), availableWidth);
            int textHeight = event.gc.textExtent(visibleText).y;
            int y = Math.max(0, (control.getClientArea().height - textHeight) / 2);
            event.gc.drawText(visibleText, 5, y, true);
            if (!trailingText.isEmpty()) {
                event.gc.drawText(trailingText, control.getClientArea().width - 5 - trailingWidth, y, true);
            }
        });
        control.addListener(SWT.MouseDown, event -> { if (event.button == 1) control.setFocus(); });
        control.addListener(SWT.MouseUp, event -> {
            if (control.getEnabled() && event.button == 1 && control.getClientArea().contains(event.x, event.y))
                action.run();
        });
        control.addListener(SWT.KeyDown, event -> {
            if (control.getEnabled() && (event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR
                    || event.character == ' ')) action.run();
        });
        control.getAccessible().addAccessibleListener(new AccessibleAdapter() {
            @Override public void getName(AccessibleEvent event) {
                String trailingText = trailingLabel.get();
                event.result = trailingText.isEmpty() ? label.get() : label.get() + ", " + trailingText;
            }
        });
        control.getAccessible().addAccessibleControlListener(new AccessibleControlAdapter() {
            @Override public void getRole(AccessibleControlEvent event) { event.detail = ACC.ROLE_PUSHBUTTON; }
        });
        return control;
    }

    /** Normalizes agent-provided titles so SWT never lays out a recent-chat row on multiple lines. */
    private static String chatTitle(String title) {
        return title == null ? "" : title.replaceAll("\\R", " ").replaceAll("\\s+", " ").trim();
    }

    /** Fits a single-line label to its Canvas using the active platform font metrics. */
    private static String ellipsize(GC gc, String text, int availableWidth) {
        if (availableWidth <= 0 || text.isEmpty() || gc.textExtent(text).x <= availableWidth) return text;
        String ellipsis = "…";
        int ellipsisWidth = gc.textExtent(ellipsis).x;
        if (ellipsisWidth > availableWidth) return "";
        int end = text.length();
        while (end > 0 && gc.textExtent(text.substring(0, end)).x + ellipsisWidth > availableWidth) {
            end = text.offsetByCodePoints(end, -1);
        }
        return text.substring(0, end) + ellipsis;
    }

    private void openRecent(int index) {
        if (index >= recentSessions.size()) return;
        RecentSession entry = recentSessions.get(index);
        if (entry.open() != null) {
            showChatPage(true, true);
            sessionService.select(entry.open());
        } else if (entry.saved() != null) {
            showChatPage(true, true);
            sessionService.restore(entry.saved());
        }
    }

    private void showAllSessions() {
        ElementListSelectionDialog dialog = new ElementListSelectionDialog(getSite().getShell(), new LabelProvider() {
            @Override public String getText(Object element) { return ((RecentSession) element).label(); }
        });
        dialog.setTitle("All chats");
        dialog.setMessage("Select a chat to open");
        dialog.setElements(recentSessions.toArray());
        if (dialog.open() != Dialog.OK) return;
        RecentSession selected = (RecentSession) dialog.getFirstResult();
        if (selected == null) return;
        if (selected.open() != null) {
            showChatPage(true, true);
            sessionService.select(selected.open());
        } else if (selected.saved() != null && activeSession != null && !activeSession.isBusy()) {
            showChatPage(true, true);
            sessionService.restore(selected.saved());
        }
    }

    private void showChatPage(boolean showChat, boolean animate) {
        if (pageHost == null || pageHost.isDisposed() || sessionsPage == null || chatPage == null) return;
        if (chatPageVisible == showChat && (showChat ? chatPage.getVisible() : sessionsPage.getVisible())) return;

        chatPageVisible = showChat;
        updateHeaderTitle();
        int generation = ++pageAnimationGeneration;
        var area = pageHost.getClientArea();
        int width = area.width;
        int height = area.height;
        if (!animate || width <= 0 || height <= 0) {
            finishPageTransition(showChat);
            return;
        }

        if (showChat) {
            showControl(backButton, true);
            headerLayout();
        }
        if (!sessionsPage.getVisible()) sessionsPage.setBounds(-width, 0, width, height);
        if (!chatPage.getVisible()) chatPage.setBounds(width, 0, width, height);
        sessionsPage.setVisible(true);
        chatPage.setVisible(true);
        int sessionsStart = sessionsPage.getBounds().x;
        int chatStart = chatPage.getBounds().x;
        int sessionsEnd = showChat ? -width : 0;
        int chatEnd = showChat ? 0 : width;
        long started = System.nanoTime();
        int durationMillis = 200;

        Runnable animation = new Runnable() {
            @Override public void run() {
                if (generation != pageAnimationGeneration || pageHost.isDisposed()) return;
                double elapsedMillis = (System.nanoTime() - started) / 1_000_000d;
                double progress = Math.min(1d, elapsedMillis / durationMillis);
                double eased = 1d - Math.pow(1d - progress, 3d);
                int sessionsX = sessionsStart + (int) Math.round((sessionsEnd - sessionsStart) * eased);
                int chatX = chatStart + (int) Math.round((chatEnd - chatStart) * eased);
                sessionsPage.setBounds(sessionsX, 0, width, height);
                chatPage.setBounds(chatX, 0, width, height);
                if (progress < 1d) {
                    pageHost.getDisplay().timerExec(16, this);
                } else {
                    finishPageTransition(showChat);
                }
            }
        };
        pageHost.getDisplay().timerExec(0, animation);
    }

    private void finishPageTransition(boolean showChat) {
        if (pageHost == null || pageHost.isDisposed()) return;
        var area = pageHost.getClientArea();
        sessionsPage.setBounds(showChat ? -area.width : 0, 0, area.width, area.height);
        chatPage.setBounds(showChat ? 0 : area.width, 0, area.width, area.height);
        sessionsPage.setVisible(!showChat);
        chatPage.setVisible(showChat);
        showControl(backButton, showChat);
        headerLayout();
    }

    private void layoutPages() {
        if (pageHost == null || pageHost.isDisposed() || sessionsPage == null || chatPage == null) return;
        var area = pageHost.getClientArea();
        sessionsPage.setBounds(chatPageVisible ? -area.width : 0, 0, area.width, area.height);
        chatPage.setBounds(chatPageVisible ? 0 : area.width, 0, area.width, area.height);
    }

    private void headerLayout() {
        if (backButton != null && !backButton.isDisposed()) backButton.getParent().layout(true, true);
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
        if (chatPageVisible && activeSession != null && activeSession.sessionName != null
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
