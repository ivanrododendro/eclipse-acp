package dev.eclipseacp.client.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.jface.bindings.keys.SWTKeySupport;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleControlAdapter;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.custom.CCombo;
import org.eclipse.ui.IWorkbenchPage;

import dev.eclipseacp.client.agent.ConfigOption;
import dev.eclipseacp.client.agent.Usage;

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

        collaborationModeSelector = new CCombo(footer, SWT.READ_ONLY | SWT.FLAT);
        collaborationModeSelector.setBackground(footerBackground);
        collaborationModeSelector.setToolTipText("Session mode for the active ACP session");
        collaborationModeSelector.setEnabled(false);
        collaborationModeSelector.addListener(SWT.Selection, ignored -> changeConfigOption(collaborationModeSelector,
                AgentConfigOptions.sessionMode(activeSession), "Session mode"));

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
        refreshModelSelector();
        refreshConfigSelector(collaborationModeSelector, AgentConfigOptions.sessionMode(activeSession),
                "Session mode for the active ACP session");
        showControl(modelSelector, modelSelector.getItemCount() > 0);
        showControl(collaborationModeSelector, collaborationModeSelector.getItemCount() > 0);
        prompt.setEnabled(connected);
        composer.getParent().layout(true, true);
    }

    private void resetWithoutSession() {
        if (!prompt.getText().isEmpty()) prompt.setText("");
        prompt.setEnabled(false);
        slashCommands.close();
        resetSelector(modelSelector, "Model for the active ACP session");
        resetSelector(collaborationModeSelector, "Session mode for the active ACP session");
        showControl(modelSelector, false);
        showControl(collaborationModeSelector, false);
        contextUsage.setUsage(null);
        showControl(contextUsage, false);
        sendButton.setEnabled(false);
        stopButton.setEnabled(false);
        showControl(sendButton, true);
        showControl(stopButton, false);
    }

    private static void resetSelector(CCombo selector, String tooltip) {
        selector.setEnabled(false);
        selector.removeAll();
        selector.setToolTipText(tooltip);
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

    /** Keyboard-first, non-modal palette for commands advertised by an ACP agent. */
    private static final class SlashCommandCompletion {
        private static final int MAX_RESULTS = 6;
        private final Text prompt;
        private final java.util.function.Supplier<Map<String, String>> commands;
        private Shell popup;
        private Table results;

        SlashCommandCompletion(Text prompt, java.util.function.Supplier<Map<String, String>> commands) {
            this.prompt = prompt;
            this.commands = commands;
            prompt.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent event) { handleKey(event); }
            });
            prompt.addModifyListener(event -> refresh());
            prompt.addListener(SWT.FocusOut, event -> closeAfterFocusChange());
            prompt.addDisposeListener(event -> close());
        }

        private void closeAfterFocusChange() {
            prompt.getDisplay().asyncExec(() -> {
                if (prompt.isDisposed() || !isOpen()) return;
                Control focus = prompt.getDisplay().getFocusControl();
                if (focus != prompt && focus != results) close();
            });
        }

        private void handleKey(KeyEvent event) {
            if (!isOpen()) return;
            switch (event.keyCode) {
                case SWT.ARROW_DOWN -> { select(1); event.doit = false; }
                case SWT.ARROW_UP -> { select(-1); event.doit = false; }
                case SWT.ESC -> { close(); event.doit = false; }
                case SWT.TAB, SWT.CR, SWT.KEYPAD_CR -> { accept(); event.doit = false; }
                default -> { }
            }
        }

        private void refresh() {
            String query = commandQuery();
            if (query == null || commands.get().isEmpty()) { close(); return; }
            List<String> matches = slashCommandMatches(commands.get(), query);
            if (matches.isEmpty()) { close(); return; }
            ensurePopup();
            String selected = results.getSelectionCount() == 0 ? null
                    : (String) results.getSelection()[0].getData("command");
            results.removeAll();
            for (String name : matches) {
                TableItem item = new TableItem(results, SWT.NONE);
                String description = commands.get().getOrDefault(name, "");
                item.setText("/" + name + (description.isBlank() ? "" : "   " + description));
                item.setData("command", name);
                if (name.equals(selected)) results.select(results.getItemCount() - 1);
            }
            if (results.getSelectionCount() == 0) results.select(0);
            position();
            popup.setVisible(true);
        }

        private String commandQuery() {
            int caret = prompt.getCaretPosition();
            String beforeCaret = prompt.getText().substring(0, caret);
            int lineStart = beforeCaret.lastIndexOf('\n') + 1;
            String token = beforeCaret.substring(lineStart);
            if (!token.startsWith("/") || token.indexOf(' ') >= 0 || token.indexOf('\t') >= 0) return null;
            return token.substring(1);
        }

        private void ensurePopup() {
            if (isOpen()) return;
            popup = new Shell(prompt.getShell(), SWT.ON_TOP | SWT.TOOL | SWT.NO_FOCUS);
            popup.setLayout(new FillLayout());
            results = new Table(popup, SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER | SWT.V_SCROLL);
            results.addListener(SWT.FocusOut, event -> closeAfterFocusChange());
            results.addListener(SWT.MouseDoubleClick, event -> accept());
            results.addListener(SWT.Selection, event -> { if (event.detail == SWT.DEFAULT) accept(); });
        }

        private void position() {
            Point origin = prompt.toDisplay(0, 0);
            int width = Math.max(320, prompt.getSize().x - 12);
            int height = Math.min(MAX_RESULTS, results.getItemCount()) * results.getItemHeight() + 4;
            popup.setBounds(origin.x, Math.max(0, origin.y - height - 6), width, height);
        }

        private void select(int delta) {
            int count = results.getItemCount();
            if (count == 0) return;
            int current = results.getSelectionIndex();
            results.select((current + delta + count) % count);
        }

        private void accept() {
            if (!isOpen() || results.getSelectionCount() == 0) return;
            String name = (String) results.getSelection()[0].getData("command");
            int caret = prompt.getCaretPosition();
            String text = prompt.getText();
            int lineStart = text.lastIndexOf('\n', Math.max(0, caret - 1)) + 1;
            String replacement = "/" + name + " ";
            prompt.setText(text.substring(0, lineStart) + replacement + text.substring(caret));
            prompt.setSelection(lineStart + replacement.length());
            close();
        }

        private boolean isOpen() { return popup != null && !popup.isDisposed(); }
        private void close() { if (isOpen()) popup.dispose(); popup = null; results = null; }
    }

    static List<String> slashCommandMatches(Map<String, String> commands, String query) {
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return commands.keySet().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparingInt((String name) -> slashCommandRank(name, needle))
                        .thenComparing(String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static int slashCommandRank(String name, String needle) {
        String candidate = name.toLowerCase(Locale.ROOT);
        if (candidate.equals(needle)) return 0;
        return candidate.startsWith(needle) ? 1 : 2;
    }

    /** Compact, colour-coded context-window meter for the composer footer. */
    private static final class ContextUsageIndicator extends Canvas {
        private static final int WIDTH = 128;
        private static final int HEIGHT = 24;
        private Long used;
        private Long size;

        ContextUsageIndicator(Composite parent, Color background) {
            super(parent, SWT.DOUBLE_BUFFERED);
            setBackground(background);
            setToolTipText("Context window usage");
            getAccessible().addAccessibleListener(new AccessibleAdapter() {
                @Override public void getName(AccessibleEvent event) { event.result = accessibleText(); }
            });
            getAccessible().addAccessibleControlListener(new AccessibleControlAdapter() {
                @Override public void getRole(AccessibleControlEvent event) { event.detail = ACC.ROLE_PROGRESSBAR; }
            });
            addPaintListener(event -> paint(event.gc));
        }

        @Override public Point computeSize(int wHint, int hHint, boolean changed) {
            return new Point(wHint == SWT.DEFAULT ? WIDTH : wHint, hHint == SWT.DEFAULT ? HEIGHT : hHint);
        }

        void setUsage(Usage usage) {
            Long nextUsed = usage == null ? null : usage.used();
            Long nextSize = usage == null ? null : usage.size();
            if (java.util.Objects.equals(used, nextUsed) && java.util.Objects.equals(size, nextSize)) return;
            used = nextUsed;
            size = nextSize;
            boolean available = isAvailable();
            setVisible(available);
            setToolTipText(available ? accessibleText() : "Context window usage is not available from this agent");
            if (!isDisposed()) redraw();
        }

        boolean isAvailable() { return used != null && size != null && size > 0; }

        private void paint(GC gc) {
            if (!isAvailable()) return;
            var area = getClientArea();
            int percent = (int) Math.min(100, Math.round(used * 100d / size));
            Color rail = getDisplay().getSystemColor(SWT.COLOR_WIDGET_LIGHT_SHADOW);
            Color ink = getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY);
            Color accent = getDisplay().getSystemColor(percent < 70 ? SWT.COLOR_DARK_CYAN
                    : percent < 90 ? SWT.COLOR_DARK_YELLOW : SWT.COLOR_DARK_RED);
            int gaugeWidth = 44;
            int gaugeHeight = 6;
            int gaugeY = (area.height - gaugeHeight) / 2;
            gc.setAntialias(SWT.ON);
            gc.setBackground(rail);
            gc.fillRoundRectangle(0, gaugeY, gaugeWidth, gaugeHeight, gaugeHeight, gaugeHeight);
            int fillWidth = Math.max(percent == 0 ? 0 : 3, gaugeWidth * percent / 100);
            gc.setBackground(accent);
            gc.fillRoundRectangle(0, gaugeY, fillWidth, gaugeHeight, gaugeHeight, gaugeHeight);
            gc.setForeground(ink);
            gc.drawText(compact(used) + " / " + compact(size), gaugeWidth + 7,
                    (area.height - gc.getFontMetrics().getHeight()) / 2, true);
        }

        private String accessibleText() {
            if (!isAvailable()) return "Context window usage is not available";
            return "Context window: " + compact(used) + " of " + compact(size) + " tokens ("
                    + Math.round(used * 100d / size) + "% used)";
        }

        private static String compact(long value) {
            if (value < 1_000) return Long.toString(value);
            if (value < 1_000_000) return Math.round(value / 1_000d) + "k";
            return String.format(java.util.Locale.ROOT, "%.1fM", value / 1_000_000d);
        }
    }
}
