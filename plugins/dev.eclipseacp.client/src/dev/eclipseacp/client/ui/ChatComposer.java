package dev.eclipseacp.client.ui;

import java.util.ArrayList;
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
import org.eclipse.swt.custom.CCombo;
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
    private IconButton sendButton;
    private IconButton stopButton;
    private IconButton attachButton;
    private CCombo modelSelector;
    private CCombo collaborationModeSelector;
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

        Composite footer = new Composite(composer, SWT.NONE);
        footer.setBackground(footerBackground);
        footer.setBackgroundMode(SWT.INHERIT_FORCE);
        footer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout footerLayout = new GridLayout(5, false);
        footerLayout.marginWidth = footerLayout.marginHeight = 0;
        footerLayout.marginTop = 2;
        footerLayout.horizontalSpacing = 6;
        footer.setLayout(footerLayout);

        attachButton = new IconButton(footer, icon.apply("plus"), "Attach (not available yet)", () -> { });
        attachButton.setEnabled(false);

        collaborationModeSelector = new CCombo(footer, SWT.READ_ONLY | SWT.FLAT);
        collaborationModeSelector.setBackground(footerBackground);
        collaborationModeSelector.setToolTipText("Session mode for the active ACP session");
        collaborationModeSelector.setEnabled(false);
        collaborationModeSelector.addListener(SWT.Selection, ignored -> changeConfigOption(collaborationModeSelector,
                AgentConfigOptions.sessionMode(activeSession), "Session mode"));

        Label spacer = new Label(footer, SWT.NONE);
        spacer.setBackground(footerBackground);
        GridData spacerData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        spacerData.widthHint = 0;
        spacerData.heightHint = 0;
        spacer.setLayoutData(spacerData);

        modelSelector = new CCombo(footer, SWT.READ_ONLY | SWT.FLAT);
        modelSelector.setBackground(footerBackground);
        modelSelector.setToolTipText("Model for the active ACP session");
        modelSelector.setEnabled(false);
        modelSelector.addListener(SWT.Selection, ignored -> changeConfigOption(modelSelector,
                AgentConfigOptions.model(activeSession), "Model"));

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
        activeSession = sessions.activeSession();
        boolean connected = activeSession != null && activeSession.isConnected();
        boolean busy = activeSession != null && activeSession.isBusy();
        updateSendButton();
        showControl(sendButton, !busy);
        showControl(stopButton, busy);
        stopButton.setEnabled(connected && activeSession.agentMessageOpen);
        refreshModelSelector();
        refreshConfigSelector(collaborationModeSelector, AgentConfigOptions.sessionMode(activeSession),
                "Session mode for the active ACP session");
        showControl(modelSelector, modelSelector.getItemCount() > 0);
        showControl(collaborationModeSelector, collaborationModeSelector.getItemCount() > 0);
        prompt.setEnabled(connected);
        composer.getParent().layout(true, true);
    }

    private void sendPrompt() {
        String text = prompt.getText().trim();
        if (text.isEmpty() || activeSession == null || !activeSession.isConnected() || activeSession.isBusy()) return;
        prompt.setText("");
        if (chatPageVisible.getAsBoolean()) sendPrompt(activeSession, text);
        else sendInNewSession.accept(text);
    }

    private void sendPrompt(ChatSessionModel session, String text) {
        sessions.sendPrompt(session, EclipseContext.expand(text, session.project, page));
    }

    void prepareInput(ChatSessionModel session, String initialPrompt) {
        if (initialPrompt != null && !initialPrompt.isBlank()) {
            sendPrompt(session, initialPrompt);
        } else if (session == activeSession) {
            if (session.pendingInputText != null) {
                String text = session.pendingInputText;
                session.pendingInputText = null;
                prompt.setText(text);
                prompt.setSelection(text.length());
            }
            setFocus();
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

    private void changeConfigOption(CCombo selector, ConfigOption option, String optionName) {
        ChatSessionModel session = activeSession;
        int selected = selector.getSelectionIndex();
        if (session == null || !session.isConnected() || session.isBusy()
                || option == null || selected < 0 || selected >= option.choices().size()) return;
        ConfigOption.Choice choice = option.choices().get(selected);
        selector.setEnabled(false);
        sessions.changeConfigOption(session, option, choice.value(), optionName + " changed to " + choice.label(),
                "Could not change " + optionName.toLowerCase(java.util.Locale.ROOT));
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
        modelSelector.setEnabled(activeSession != null && activeSession.client != null && !activeSession.isBusy());
        modelSelector.setToolTipText(option.description().isBlank() ? "Model for the active ACP session" : option.description());
    }

    private void refreshConfigSelector(CCombo selector, ConfigOption option, String defaultTooltip) {
        if (selector == null || selector.isDisposed()) return;
        selector.removeAll();
        if (option == null) { selector.setEnabled(false); return; }
        String current = AgentConfigOptions.text(option.value());
        for (ConfigOption.Choice choice : option.choices()) {
            selector.add(choice.label());
            if (choice.value().equals(current)) selector.select(selector.getItemCount() - 1);
        }
        selector.setEnabled(activeSession != null && activeSession.client != null && !activeSession.isBusy());
        selector.setToolTipText(option.description().isBlank() ? defaultTooltip : option.description());
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
