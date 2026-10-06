package dev.eclipseacp.client.ui;

import java.util.ArrayList;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.jface.bindings.keys.SWTKeySupport;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbenchPage;

import dev.eclipseacp.client.agent.ConfigOption;

/** SWT prompt editor and agent option selectors. Session actions belong to AcpSessionService. */
final class ChatComposer {
    private static final int SEND_SHORTCUT = SWT.MOD1 | SWT.CR;
    private static final String SEND_TOOLTIP = "Send message ("
            + SWTKeySupport.convertAcceleratorToKeyStroke(SEND_SHORTCUT).format() + ")";

    private final AcpSessionService sessions;
    private final AcpChatDialogs dialogs;
    private final IWorkbenchPage page;
    private final BooleanSupplier chatPageVisible;
    private final Consumer<String> sendInNewSession;
    private final Composite composer;
    private Text prompt;
    private SlashCommandCompletion slashCommands;
    private IconButton sendButton;
    private IconButton stopButton;
    private IconButton attachButton;
    private ContextUsageIndicator contextUsage;
    private AgentOptionSelector modelSelector;
    private AgentOptionSelector collaborationModeSelector;
    private ChatSessionModel activeSession;

    ChatComposer(Composite parent, Font font, IWorkbenchPage page, AcpSessionService sessions,
            AcpChatDialogs dialogs, BooleanSupplier chatPageVisible, Consumer<String> sendInNewSession,
            Function<String, Image> icon) {
        this.sessions = sessions;
        this.dialogs = dialogs;
        this.page = page;
        this.chatPageVisible = chatPageVisible;
        this.sendInNewSession = sendInNewSession;
        composer = new Composite(parent, SWT.DOUBLE_BUFFERED);
        composer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        var inputBackground = parent.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND);
        var footerBackground = new Color(parent.getDisplay(), 255, 255, 255);
        composer.addDisposeListener(event -> footerBackground.dispose());
        var borderBackground = parent.getDisplay().getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW);
        composer.setBackground(parent.getBackground());
        GridLayout composerLayout = new GridLayout(1, false);
        composerLayout.marginWidth = composerLayout.marginHeight = 0;
        composerLayout.marginLeft = 14;
        composerLayout.marginRight = 14;
        composerLayout.marginTop = 3;
        composerLayout.marginBottom = 2;
        composerLayout.verticalSpacing = 8;
        composer.setLayout(composerLayout);
        composer.addPaintListener(event -> {
            var bounds = composer.getClientArea();
            event.gc.setAntialias(SWT.ON);
            event.gc.setBackground(inputBackground);
            event.gc.fillRoundRectangle(0, 0, bounds.width - 1, bounds.height - 1, 22, 22);
            event.gc.setForeground(borderBackground);
            event.gc.drawRoundRectangle(0, 0, bounds.width - 1, bounds.height - 1, 22, 22);
        });

        prompt = new Text(composer, SWT.MULTI | SWT.WRAP);
        prompt.setBackground(inputBackground);
        prompt.setFont(font);
        int minimumPromptHeight = 2 * prompt.getLineHeight();
        GridData promptData = new GridData(SWT.FILL, SWT.FILL, true, false);
        promptData.heightHint = minimumPromptHeight;
        prompt.setLayoutData(promptData);
        prompt.setMessage("Do anything");
        prompt.addModifyListener(event -> {
            if (activeSession != null) activeSession.promptDraft = prompt.getText();
            int lines = Math.max(prompt.getLineCount(), prompt.getText().length()
                    / Math.max(20, prompt.getClientArea().width / 8) + 1);
            int height = Math.max(minimumPromptHeight, Math.min(160, lines * prompt.getLineHeight()));
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
        slashCommands = new SlashCommandCompletion(prompt,
                () -> activeSession == null ? Map.of() : activeSession.commands);

        Composite footer = new Composite(composer, SWT.NONE);
        footer.setBackground(footerBackground);
        footer.setBackgroundMode(SWT.INHERIT_FORCE);
        footer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout footerLayout = new GridLayout(6, false);
        footerLayout.marginWidth = footerLayout.marginHeight = 0;
        footerLayout.marginTop = 2;
        footerLayout.horizontalSpacing = 6;
        footer.setLayout(footerLayout);

        attachButton = new IconButton(footer, icon.apply("plus"), "Attach (not available yet)", () -> { });
        attachButton.setEnabled(false);

        collaborationModeSelector = new AgentOptionSelector(footer, footerBackground,
                "Session mode", false, (option, choice) -> changeConfigOption(option, choice, "Session mode"));

        contextUsage = new ContextUsageIndicator(footer, footerBackground);
        GridData contextUsageData = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        contextUsageData.exclude = true;
        contextUsage.setLayoutData(contextUsageData);
        contextUsage.setVisible(false);

        Label spacer = new Label(footer, SWT.NONE);
        spacer.setBackground(footerBackground);
        GridData spacerData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        spacerData.widthHint = 0;
        spacerData.heightHint = 0;
        spacer.setLayoutData(spacerData);

        modelSelector = new AgentOptionSelector(footer, footerBackground,
                "Model", true, (option, choice) -> changeConfigOption(option, choice, "Model"));

        Composite submit = new Composite(footer, SWT.NONE);
        submit.setBackground(footerBackground);
        GridLayout submitLayout = new GridLayout(1, false);
        submitLayout.marginWidth = submitLayout.marginHeight = 0;
        submit.setLayout(submitLayout);
        sendButton = new IconButton(submit, icon.apply("circle-arrow-up"), SEND_TOOLTIP,
                this::sendPrompt);
        sendButton.setEnabled(false);

        stopButton = new IconButton(submit, icon.apply("square"), "Stop generating the response",
                sessions::cancel);
        stopButton.setEnabled(false);
        applyFooterBackground(footer, footerBackground);
    }

    private static void applyFooterBackground(Control control, Color background) {
        // This custom white surface must not be recolored by the workbench CSS theme.
        control.setData("org.eclipse.e4.ui.css.disabled", Boolean.TRUE);
        control.setBackground(background);
        control.setForeground(control.getDisplay().getSystemColor(SWT.COLOR_BLACK));
        if (control instanceof Composite composite) {
            for (Control child : composite.getChildren()) {
                applyFooterBackground(child, background);
            }
        }
    }

    void update() {
        if (composer.isDisposed()) return;
        ChatSessionModel nextSession = sessions.activeSession();
        if (activeSession != nextSession) {
            saveDraft();
            activeSession = nextSession;
            restoreActiveDraft();
        }
        composer.setEnabled(activeSession != null);
        if (activeSession == null) {
            resetWithoutSession();
            composer.getParent().layout(true, true);
            return;
        }
        boolean connected = activeSession != null && activeSession.isConnected();
        boolean busy = activeSession != null && activeSession.isBusy();
        contextUsage.setUsage(activeSession == null ? null : activeSession.usage);
        showControl(contextUsage, contextUsage.isAvailable());
        updateSendButton();
        showControl(sendButton, !busy);
        showControl(stopButton, busy);
        stopButton.setEnabled(connected && activeSession.agentMessageOpen);
        modelSelector.update(AgentConfigOptions.model(activeSession), connected && !busy);
        collaborationModeSelector.update(AgentConfigOptions.sessionMode(activeSession), connected && !busy);
        prompt.setEnabled(connected);
        composer.getParent().layout(true, true);
    }

    private void resetWithoutSession() {
        if (!prompt.getText().isEmpty()) prompt.setText("");
        prompt.setEnabled(false);
        slashCommands.close();
        modelSelector.reset();
        collaborationModeSelector.reset();
        contextUsage.setUsage(null);
        showControl(contextUsage, false);
        sendButton.setEnabled(false);
        stopButton.setEnabled(false);
        showControl(sendButton, true);
        showControl(stopButton, false);
    }

    private void sendPrompt() {
        String draft = prompt.getText();
        String text = draft.trim();
        if (text.isEmpty() || activeSession == null || !activeSession.isConnected() || activeSession.isBusy()) return;
        activeSession.promptDraft = "";
        prompt.setText("");
        if (chatPageVisible.getAsBoolean()) sendPrompt(activeSession, draft, text);
        else sendInNewSession.accept(text);
    }

    private void sendPrompt(ChatSessionModel session, String text) {
        sendPrompt(session, text, text);
    }

    private void sendPrompt(ChatSessionModel session, String draft, String text) {
        sessions.sendPrompt(session, draft, EclipseContext.expand(text, session.project, page));
    }

    void prepareInput(ChatSessionModel session, String initialPrompt) {
        if (initialPrompt != null && !initialPrompt.isBlank()) {
            sendPrompt(session, initialPrompt);
        } else if (session == activeSession) {
            if (session.pendingInputText != null) {
                String text = session.pendingInputText;
                session.pendingInputText = null;
                restoreDraft(session, text);
            }
            setFocus();
        }
    }

    void restoreDraft(ChatSessionModel session, String text) {
        if (session == null) return;
        session.promptDraft = text == null ? "" : text;
        if (session == activeSession && !prompt.isDisposed()) {
            prompt.setText(session.promptDraft);
            prompt.setSelection(session.promptDraft.length());
        }
    }

    private void saveDraft() {
        if (activeSession != null && prompt != null && !prompt.isDisposed()) {
            activeSession.promptDraft = prompt.getText();
        }
    }

    private void restoreActiveDraft() {
        if (prompt == null || prompt.isDisposed()) return;
        String draft = activeSession == null ? "" : activeSession.promptDraft;
        if (!prompt.getText().equals(draft)) {
            prompt.setText(draft);
            prompt.setSelection(draft.length());
        }
    }

    boolean canEditConfigOption() {
        return activeSession != null && activeSession.isConnected() && !activeSession.isBusy()
                && !activeSession.configOptions.isEmpty();
    }

    void editConfigOption() {
        ChatSessionModel session = activeSession;
        if (!canEditConfigOption()) return;
        dialogs.editConfigOptions(new ArrayList<>(session.configOptions.values())).forEach((option, value) ->
                sessions.changeConfigOption(session, option, value, "Updated " + option.name(),
                        "Could not update " + option.name()));
    }

    private void changeConfigOption(ConfigOption option, ConfigOption.Choice choice, String optionName) {
        ChatSessionModel session = activeSession;
        if (session == null || !session.isConnected() || session.isBusy()) return;
        sessions.changeConfigOption(session, option, choice.value(), optionName + " changed to " + choice.label(),
                "Could not change " + optionName.toLowerCase(java.util.Locale.ROOT));
    }

    private void updateSendButton() {
        if (sendButton == null || sendButton.isDisposed() || prompt == null || prompt.isDisposed()) return;
        boolean enabled = activeSession != null && activeSession.isConnected()
                && !activeSession.isBusy() && !prompt.getText().isBlank();
        if (sendButton.getEnabled() != enabled) sendButton.setEnabled(enabled);
    }

    private static void showControl(Control control, boolean visible) {
        control.setVisible(visible);
        GridData data = control.getLayoutData() instanceof GridData existing ? existing : new GridData();
        data.exclude = !visible;
        control.setLayoutData(data);
    }

    void setFocus() {
        prompt.setFocus();
    }
}
