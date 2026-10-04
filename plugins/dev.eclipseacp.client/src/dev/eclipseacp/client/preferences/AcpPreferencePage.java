package dev.eclipseacp.client.preferences;

import java.io.IOException;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import dev.eclipseacp.client.agent.AgentProvider;

public final class AcpPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {
    private List providerList; private Text name; private Text command; private Text arguments; private Label providerValidation; private Button hideAgentCommandsInChat; private Button debugAcpMessages; private Spinner visibleRecentSessions; private AgentProviderRegistry registry;
    public AcpPreferencePage() {
        setPreferenceStore(AcpPreferences.store());
    }
    @Override protected Composite createContents(Composite parent) {
        registry = new AgentProviderRegistry(AcpPreferences.store());
        Composite root = new Composite(parent, SWT.NONE); root.setLayout(new GridLayout(2, false));
        providerList = new List(root, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL); providerList.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        Composite edit = new Composite(root, SWT.NONE); edit.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false)); edit.setLayout(new GridLayout(2, false));
        new Label(edit, SWT.NONE).setText("Name:"); name = field(edit); new Label(edit, SWT.NONE).setText("Command:"); command = field(edit); new Label(edit, SWT.NONE).setText("Arguments:"); arguments = field(edit);
        providerValidation = new Label(edit, SWT.WRAP); GridData validationData = new GridData(SWT.FILL, SWT.CENTER, true, false); validationData.horizontalSpan = 2; providerValidation.setLayoutData(validationData);
        name.addListener(SWT.Modify, e -> validateProviderInput()); command.addListener(SWT.Modify, e -> validateProviderInput());
        Button fresh = new Button(edit, SWT.PUSH); fresh.setText("New provider"); fresh.addListener(SWT.Selection, e -> { providerList.deselectAll(); name.setText(""); command.setText(""); arguments.setText(""); });
        Button save = new Button(edit, SWT.PUSH); save.setText("Add / update"); save.addListener(SWT.Selection, e -> saveProvider());
        Button select = new Button(edit, SWT.PUSH); select.setText("Set as default"); select.addListener(SWT.Selection, e -> selectProvider());
        Button remove = new Button(edit, SWT.PUSH); remove.setText("Remove"); remove.addListener(SWT.Selection, e -> removeProvider());
        hideAgentCommandsInChat = new Button(root, SWT.CHECK);
        hideAgentCommandsInChat.setText("Hide agent commands from the chat transcript (show them only in the status bar)");
        hideAgentCommandsInChat.setSelection(AcpPreferences.store().getBoolean(AcpPreferences.HIDE_AGENT_COMMANDS_IN_CHAT));
        GridData commandsData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        commandsData.horizontalSpan = 2;
        hideAgentCommandsInChat.setLayoutData(commandsData);
        debugAcpMessages = new Button(root, SWT.CHECK);
        debugAcpMessages.setText("Enable DEBUG logging of all ACP JSON-RPC messages (may include prompts, file contents, and secrets)");
        debugAcpMessages.setSelection(AcpPreferences.store().getBoolean(AcpPreferences.DEBUG_ACP_MESSAGES));
        GridData debugData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        debugData.horizontalSpan = 2;
        debugAcpMessages.setLayoutData(debugData);
        new Label(root, SWT.NONE).setText("Recent chats shown in the chat picker:");
        visibleRecentSessions = new Spinner(root, SWT.BORDER);
        visibleRecentSessions.setMinimum(AcpPreferences.MIN_VISIBLE_RECENT_SESSIONS);
        visibleRecentSessions.setMaximum(AcpPreferences.MAX_VISIBLE_RECENT_SESSIONS);
        visibleRecentSessions.setSelection(AcpPreferences.visibleRecentSessions(AcpPreferences.store()));
        providerList.addListener(SWT.Selection, e -> loadSelected()); refresh();
        if (registry.hasInvalidStoredConfiguration()) setMessage("The saved provider configuration is invalid. It will remain unchanged until you save a replacement; the original JSON will be kept for recovery.", WARNING);
        return root;
    }
    private Text field(Composite parent) { Text t = new Text(parent, SWT.BORDER); t.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false)); return t; }
    private void refresh() { providerList.removeAll(); for (AgentProvider p : registry.list()) providerList.add(p.name() + (p.id().equals(registry.active().id()) ? " (active)" : "")); if (providerList.getItemCount() > 0) providerList.select(0); loadSelected(); }
    private void loadSelected() { int i = providerList.getSelectionIndex(); if (i >= 0) { AgentProvider p = registry.list().get(i); name.setText(p.name()); command.setText(p.command()); arguments.setText(p.arguments()); } }
    private void saveProvider() {
        String validation = providerValidation();
        if (validation != null) { showProviderValidation(validation); return; }
        try {
            int i = providerList.getSelectionIndex(); String id = i >= 0 ? registry.list().get(i).id() : providerId(name.getText());
            AgentProvider p = new AgentProvider(id, name.getText(), command.getText(), arguments.getText());
            if (i >= 0) registry.update(p); else registry.add(p);
            showProviderValidation(null); refresh();
        } catch (IllegalArgumentException error) { showProviderValidation(error.getMessage()); }
    }
    private void selectProvider() { try { int i = providerList.getSelectionIndex(); if (i >= 0) { registry.select(registry.list().get(i).id()); refresh(); } } catch (IllegalArgumentException error) { showProviderValidation(error.getMessage()); } }
    private void removeProvider() { try { int i = providerList.getSelectionIndex(); if (i >= 0) { registry.remove(registry.list().get(i).id()); refresh(); } } catch (IllegalArgumentException | IllegalStateException error) { showProviderValidation(error.getMessage()); } }
    private void validateProviderInput() { showProviderValidation(providerValidation()); }
    private String providerValidation() { if (name.getText().trim().isEmpty()) return "Provider name is required."; if (command.getText().trim().isEmpty()) return "Provider command is required."; if (providerList.getSelectionIndex() < 0 && providerId(name.getText()).isEmpty()) return "Provider name must contain a letter or number."; return null; }
    private static String providerId(String providerName) { return providerName.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", ""); }
    private void showProviderValidation(String message) { providerValidation.setText(message == null ? "" : message); providerValidation.getParent().layout(true, true); }
    @Override public boolean performOk() {
        IPreferenceStore store = getPreferenceStore();
        StoredValues before = StoredValues.read(store);
        try {
            registry.commitTo(store);
            store.setValue(AcpPreferences.HIDE_AGENT_COMMANDS_IN_CHAT, hideAgentCommandsInChat.getSelection());
            store.setValue(AcpPreferences.DEBUG_ACP_MESSAGES, debugAcpMessages.getSelection());
            store.setValue(AcpPreferences.VISIBLE_RECENT_SESSIONS, visibleRecentSessions.getSelection());
            AcpPreferences.save();
            registry.committed();
            setErrorMessage(null);
            return true;
        } catch (IOException error) {
            before.restore(store);
            setErrorMessage("Could not save ACP preferences: " + error.getMessage());
            return false;
        }
    }
    @Override protected void performApply() { performOk(); }
    @Override protected void performDefaults() {
        registry.resetToDefaults(); refresh();
        hideAgentCommandsInChat.setSelection(AcpPreferences.DEFAULT_HIDE_AGENT_COMMANDS_IN_CHAT);
        debugAcpMessages.setSelection(AcpPreferences.DEFAULT_DEBUG_ACP_MESSAGES);
        visibleRecentSessions.setSelection(AcpPreferences.DEFAULT_VISIBLE_RECENT_SESSIONS);
        showProviderValidation(null);
    }
    @Override public void init(IWorkbench workbench) { }

    private record StoredValues(String providersJson, String recoveryJson, String activeProvider,
            boolean hideAgentCommands, boolean debugMessages, int visibleSessions) {
        static StoredValues read(IPreferenceStore store) {
            return new StoredValues(store.getString(AcpPreferences.PROVIDERS_JSON),
                    store.getString(AcpPreferences.PROVIDERS_JSON_RECOVERY), store.getString(AcpPreferences.ACTIVE_PROVIDER),
                    store.getBoolean(AcpPreferences.HIDE_AGENT_COMMANDS_IN_CHAT),
                    store.getBoolean(AcpPreferences.DEBUG_ACP_MESSAGES), store.getInt(AcpPreferences.VISIBLE_RECENT_SESSIONS));
        }
        void restore(IPreferenceStore store) {
            store.setValue(AcpPreferences.PROVIDERS_JSON, providersJson);
            store.setValue(AcpPreferences.PROVIDERS_JSON_RECOVERY, recoveryJson);
            store.setValue(AcpPreferences.ACTIVE_PROVIDER, activeProvider);
            store.setValue(AcpPreferences.HIDE_AGENT_COMMANDS_IN_CHAT, hideAgentCommands);
            store.setValue(AcpPreferences.DEBUG_ACP_MESSAGES, debugMessages);
            store.setValue(AcpPreferences.VISIBLE_RECENT_SESSIONS, visibleSessions);
        }
    }
}
