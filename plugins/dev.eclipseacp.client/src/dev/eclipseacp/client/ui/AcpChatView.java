package dev.eclipseacp.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.ImageRegistry;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

import dev.eclipseacp.client.agent.SessionInfo;

/** Composes the chat controls and presents the selected project's session. */
public final class AcpChatView extends ViewPart {
    public static final String ID = "dev.eclipseacp.client.views.chat";
    private ChatTranscript transcript;
    private ChatComposer composer;
    private Button newSessionButton;
    private Button closeButton;
    private Combo projectSelector;
    private Combo sessionSelector;
    private Button applyButton;
    private Button rejectButton;
    private Button undoButton;
    private Composite reviewBar;
    private Label reviewSummary;
    private Label status;
    private ChatSessionModel activeSession;
    private final List<SessionInfo> sessionSelectorEntries = new ArrayList<>();
    private final ImageRegistry iconRegistry = new ImageRegistry();
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

        Composite header = new Composite(parent, SWT.NONE);
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        org.eclipse.swt.layout.RowLayout headerLayout = new org.eclipse.swt.layout.RowLayout();
        headerLayout.wrap = true;
        headerLayout.center = true;
        headerLayout.spacing = 4;
        headerLayout.marginLeft = headerLayout.marginRight = 0;
        headerLayout.marginTop = headerLayout.marginBottom = 0;
        header.setLayout(headerLayout);

        Composite projectInfo = new Composite(header, SWT.NONE);
        GridLayout projectInfoLayout = new GridLayout(2, false);
        projectInfoLayout.marginWidth = 0;
        projectInfoLayout.marginHeight = 0;
        projectInfo.setLayout(projectInfoLayout);

        Label projectLabel = new Label(projectInfo, SWT.NONE);
        projectLabel.setText("Project:");
        projectSelector = new Combo(projectInfo, SWT.DROP_DOWN | SWT.READ_ONLY);
        projectSelector.setToolTipText("Projects with an open ACP session");
        GridData projectSelectorData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        projectSelectorData.widthHint = 240;
        projectSelector.setLayoutData(projectSelectorData);
        projectSelector.addListener(SWT.Selection, ignored -> selectProjectFromCombo());

        Composite sessionInfo = new Composite(header, SWT.NONE);
        GridLayout sessionInfoLayout = new GridLayout(4, false);
        sessionInfoLayout.marginWidth = 0;
        sessionInfoLayout.marginHeight = 0;
        sessionInfo.setLayout(sessionInfoLayout);

        Label sessionLabel = new Label(sessionInfo, SWT.NONE);
        sessionLabel.setText("Session:");
        sessionSelector = new Combo(sessionInfo, SWT.DROP_DOWN | SWT.READ_ONLY);
        sessionSelector.setToolTipText("Saved sessions for this project");
        sessionSelector.setEnabled(false);
        GridData sessionSelectorData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        sessionSelectorData.widthHint = 240;
        sessionSelector.setLayoutData(sessionSelectorData);
        sessionSelector.addListener(SWT.Selection, ignored -> selectAgentSession());

        newSessionButton = new Button(sessionInfo, SWT.PUSH);
        newSessionButton.setToolTipText("New chat in this project");
        newSessionButton.setEnabled(false);
        newSessionButton.addListener(SWT.Selection, ignored -> sessionService.newSession(null));

        closeButton = new Button(sessionInfo, SWT.PUSH);
        closeButton.setToolTipText("Close this chat");
        closeButton.setEnabled(false);
        closeButton.addListener(SWT.Selection, ignored -> sessionService.close(activeSession));

        header.addListener(SWT.Resize, ignored -> updateHeaderBlockWidths(header, projectInfo, sessionInfo));
        header.getDisplay().asyncExec(() -> updateHeaderBlockWidths(header, projectInfo, sessionInfo));

        var fontData = projectSelector.getFont().getFontData();
        String fontFamily = fontData.length == 0 ? "sans-serif" : fontData[0].getName();
        int fontSize = fontData.length == 0 ? 10 : Math.max(8, fontData[0].getHeight() - 1);
        transcript = new ChatTranscript(parent, getSite().getPage(), () -> activeSession,
                sessionService::newSession, fontFamily, fontSize);

        reviewBar = new Composite(parent, SWT.NONE);
        reviewBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        reviewBar.setLayout(new GridLayout(4, false));
        reviewSummary = new Label(reviewBar, SWT.NONE);
        reviewSummary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
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

        composer = new ChatComposer(parent, projectSelector.getFont(), getSite().getPage(),
                sessionService, dialogs, this::lucideIcon);
        ISharedImages images = PlatformUI.getWorkbench().getSharedImages();
        newSessionButton.setImage(lucideIcon("message-square-plus"));
        closeButton.setImage(lucideIcon("x"));
        applyButton.setImage(images.getImage(ISharedImages.IMG_ETOOL_SAVE_EDIT));
        rejectButton.setImage(images.getImage(ISharedImages.IMG_ETOOL_DELETE));
        undoButton.setImage(images.getImage(ISharedImages.IMG_TOOL_UNDO));

        status = new Label(parent, SWT.NONE);
        status.setText("Not connected");
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        updateControls();
    }

    private void updateHeaderBlockWidths(Composite header, Composite projectInfo, Composite sessionInfo) {
        if (header.isDisposed() || projectInfo.isDisposed() || sessionInfo.isDisposed()) return;
        int availableWidth = header.getClientArea().width;
        if (availableWidth <= 0) return;
        org.eclipse.swt.layout.RowLayout layout = (org.eclipse.swt.layout.RowLayout) header.getLayout();
        int requiredWidth = projectInfo.computeSize(SWT.DEFAULT, SWT.DEFAULT).x
                + sessionInfo.computeSize(SWT.DEFAULT, SWT.DEFAULT).x + layout.spacing;
        boolean wrap = requiredWidth > availableWidth;
        boolean changed = setHeaderBlockWidth(projectInfo, wrap ? availableWidth : SWT.DEFAULT)
                | setHeaderBlockWidth(sessionInfo, wrap ? availableWidth : SWT.DEFAULT);
        if (changed) header.layout(true);
    }

    private static boolean setHeaderBlockWidth(Composite block, int width) {
        Object data = block.getLayoutData();
        if (width == SWT.DEFAULT) {
            if (data == null) return false;
            block.setLayoutData(null);
            return true;
        }
        if (data instanceof org.eclipse.swt.layout.RowData rowData && rowData.width == width) return false;
        block.setLayoutData(new org.eclipse.swt.layout.RowData(width, SWT.DEFAULT));
        return true;
    }

    /** Called by the project/resource context-menu command. */
    public void openSessionFor(IProject project) {
        openSessionFor(project, null);
    }

    public void openSessionFor(IProject project, String initialPrompt) {
        sessionService.openSessionFor(project, initialPrompt);
    }

    private void selectProjectFromCombo() {
        int index = projectSelector.getSelectionIndex();
        List<ChatSessionModel> sessions = sessionService.sessions();
        if (index >= 0 && index < sessions.size()) sessionService.select(sessions.get(index));
    }

    private void selectSession(ChatSessionModel session) {
        activeSession = session;
        List<ChatSessionModel> sessions = sessionService.sessions();
        projectSelector.setItems(sessions.stream().map(item -> item.label).toArray(String[]::new));
        if (session != null) projectSelector.select(sessions.indexOf(session));
        transcript.render();
        updateControls();
    }

    private AcpChatSessionListener listenerFor(ChatSessionModel session) {
        return new AcpChatSessionListener(session, sessionService, dialogs, this::ui,
                action -> getSite().getShell().getDisplay().timerExec(40, action));
    }

    private void populateSessionSelector(List<SessionInfo> available) {
        if (sessionSelector == null || sessionSelector.isDisposed()) return;
        String activeName = activeSession == null || activeSession.sessionName == null
                || activeSession.sessionName.isBlank() ? "—" : activeSession.sessionName;
        sessionSelector.removeAll();
        sessionSelectorEntries.clear();
        sessionSelector.add(activeName);
        sessionSelectorEntries.add(null);
        for (SessionInfo info : available) {
            String title = info.title().isBlank() ? info.id() : info.title();
            if (!title.equals(activeName)) {
                sessionSelector.add(title);
                sessionSelectorEntries.add(info);
            }
        }
        sessionSelector.select(0);
    }

    private void selectAgentSession() {
        int index = sessionSelector.getSelectionIndex();
        SessionInfo info = index >= 0 && index < sessionSelectorEntries.size()
                ? sessionSelectorEntries.get(index) : null;
        if (info != null) sessionService.restore(info);
    }

    private void applyChanges() {
        ChatSessionModel session = activeSession;
        if (session == null) return;
        reviewOperation(session, session.changes.applyPendingAsync(),
                count -> "> Applied " + count + " reviewed file change(s).\n\n",
                "Changes applied", "Could not apply reviewed changes");
    }

    private void rejectChanges() {
        ChatSessionModel session = activeSession;
        if (session == null) return;
        int count = session.changes.pending().size();
        reviewOperation(session, session.changes.rejectPendingAsync(),
                reverted -> "> Rejected " + count + " reviewed file change(s)"
                        + (reverted == 0 ? "." : " and reverted " + reverted + " direct write(s).") + "\n\n",
                "Changes rejected", "Could not reject reviewed changes");
    }

    private void undoApply() {
        ChatSessionModel session = activeSession;
        if (session == null) return;
        reviewOperation(session, session.changes.undoAsync(),
                count -> "> Undid " + count + " applied file change(s).\n\n",
                "Changes undone", "Could not undo applied changes");
    }

    private void reviewOperation(ChatSessionModel session, CompletableFuture<Integer> operation,
            IntFunction<String> message, String success, String failure) {
        operation.whenComplete((count, error) -> ui(() -> {
            if (!sessionService.contains(session)) return;
            if (error != null) {
                sessionService.error(session, failure, error.getCause() == null ? error : error.getCause());
            } else {
                sessionService.append(session, message.apply(count));
                sessionService.setStatus(session, success);
                sessionService.changed(session);
            }
        }));
    }

    private void updateControls() {
        if (status == null || status.isDisposed()) return;
        boolean connected = activeSession != null && activeSession.isConnected();
        boolean busy = activeSession != null && activeSession.isBusy();
        newSessionButton.setEnabled(connected && !busy && activeSession.project.isOpen());
        closeButton.setEnabled(connected);
        sessionSelector.setEnabled(connected && !busy && activeSession.canListSessions());
        populateSessionSelector(activeSession == null ? List.of() : activeSession.savedSessions);
        boolean review = connected && activeSession.reviewFileChanges;
        int count = review ? activeSession.changes.pending().size() : 0;
        boolean hasDiffs = count > 0;
        applyButton.setEnabled(hasDiffs);
        rejectButton.setEnabled(hasDiffs);
        undoButton.setEnabled(review && activeSession.changes.canUndo());
        showControl(reviewBar, hasDiffs || undoButton.getEnabled());
        showControl(applyButton, hasDiffs);
        showControl(rejectButton, hasDiffs);
        showControl(undoButton, undoButton.getEnabled());
        reviewSummary.setText(hasDiffs ? count + " changed files" : "Changes applied");
        projectSelector.setEnabled(connected || !sessionService.sessions().isEmpty());
        renderStatus();
        composer.update();
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
