package dev.eclipseacp.client.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.function.Function;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;

import dev.eclipseacp.client.agent.ConfigOption;
import dev.eclipseacp.client.agent.PromptAttachment;

/** SWT prompt editor and agent option selectors. Session actions belong to AcpSessionService. */
final class ChatComposer {
    private final AcpSessionService sessions;
    private final AcpChatDialogs dialogs;
    private final IWorkbenchPage page;
    private final Composite composer;
    private Text prompt;
    private Button sendButton;
    private Button stopButton;
    private Button contextButton;
    private Button commandsButton;
    private Button settingsButton;
    private Button attachButton;
    private Combo modelSelector;
    private Combo thoughtLevelSelector;
    private Combo collaborationModeSelector;
    private Label collaborationModeLabel;
    private Label providerLabel;
    private Composite collaborationModeBar;
    private ChatSessionModel activeSession;

    ChatComposer(Composite parent, Font font, IWorkbenchPage page, AcpSessionService sessions,
            AcpChatDialogs dialogs, Function<String, Image> icon) {
        this.sessions = sessions;
        this.dialogs = dialogs;
        this.page = page;
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
        prompt.setFont(font);
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
        stopButton.addListener(SWT.Selection, ignored -> sessions.cancel());

        modelSelector = new Combo(actions, SWT.DROP_DOWN | SWT.READ_ONLY);
        modelSelector.setToolTipText("Model for the active ACP session");
        modelSelector.setEnabled(false);
        modelSelector.addListener(SWT.Selection, ignored -> changeConfigOption(modelSelector,
                AgentConfigOptions.model(activeSession), "Model"));

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
        contextButton.addListener(SWT.Selection, ignored -> insert("@file\n@selection\n@java\n@problems\n@console\n"));

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

        ISharedImages images = PlatformUI.getWorkbench().getSharedImages();
        sendButton.setImage(icon.apply("send-horizontal"));
        stopButton.setImage(images.getImage(ISharedImages.IMG_ELCL_STOP));
        contextButton.setImage(icon.apply("circle-fading-plus"));
        commandsButton.setImage(icon.apply("square-slash"));
        settingsButton.setImage(icon.apply("circle-ellipsis"));
        attachButton.setImage(images.getImage(ISharedImages.IMG_OBJ_FILE));
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
        settingsButton.setEnabled(connected && !busy && !activeSession.configOptions.isEmpty());
        refreshModelSelector();
        refreshConfigSelector(thoughtLevelSelector, AgentConfigOptions.thoughtLevel(activeSession),
                "Reasoning level for the active ACP session");
        refreshConfigSelector(collaborationModeSelector, AgentConfigOptions.sessionMode(activeSession),
                "Session mode for the active ACP session");
        providerLabel.setText(activeSession == null ? "" : "Agent : " + activeSession.provider.name());
        showControl(settingsButton, settingsButton.getEnabled());
        showControl(modelSelector, modelSelector.getItemCount() > 0);
        showControl(thoughtLevelSelector, thoughtLevelSelector.getItemCount() > 0);
        boolean sessionModeAvailable = collaborationModeSelector.getItemCount() > 0;
        showControl(collaborationModeLabel, sessionModeAvailable);
        showControl(collaborationModeSelector, sessionModeAvailable);
        showControl(collaborationModeBar, activeSession != null);
        attachButton.setText(connected && !activeSession.attachments.isEmpty()
                ? "Attach (" + activeSession.attachments.size() + ")" : "Attach");
        prompt.setEnabled(connected);
        composer.getParent().layout(true, true);
    }

    private void sendPrompt() {
        String text = prompt.getText().trim();
        if (text.isEmpty() || activeSession == null || !activeSession.isConnected() || activeSession.isBusy()) return;
        prompt.setText("");
        sendPrompt(activeSession, text);
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

    private void insert(String text) {
        if (activeSession == null || text == null) return;
        prompt.insert(text);
        setFocus();
    }

    private void chooseCommand() {
        if (activeSession != null) insert(dialogs.chooseCommand(activeSession.commands));
    }

    private void editConfigOption() {
        ChatSessionModel session = activeSession;
        if (session == null || !session.isConnected() || session.configOptions.isEmpty()) return;
        dialogs.editConfigOptions(new ArrayList<>(session.configOptions.values())).forEach((option, value) ->
                sessions.changeConfigOption(session, option, value, "Updated " + option.name(),
                        "Could not update " + option.name()));
    }

    private void attachFile() {
        ChatSessionModel session = activeSession;
        if (session == null) return;
        try {
            PromptAttachment attachment = dialogs.chooseAttachment();
            if (attachment == null) return;
            session.attachments.add(attachment);
            sessions.setStatus(session, "Attachment ready: " + attachment.path().getFileName());
            sessions.changed(session);
        } catch (IOException error) {
            sessions.error(session, "Could not attach file", error);
        }
    }

    private void changeConfigOption(Combo selector, ConfigOption option, String optionName) {
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

    private void refreshConfigSelector(Combo selector, ConfigOption option, String defaultTooltip) {
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

    void setFocus() {
        prompt.setFocus();
    }
}
