package dev.eclipseacp.client.preferences;

import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import dev.eclipseacp.client.agent.AgentProvider;

public final class AcpPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {
    private List providerList; private Text name; private Text command; private Text arguments; private Button reviewFileChanges; private Button hideAgentCommandsInChat; private Button debugAcpMessages; private AgentProviderRegistry registry;
    @Override protected Composite createContents(Composite parent) {
        registry = new AgentProviderRegistry(AcpPreferences.store());
        Composite root = new Composite(parent, SWT.NONE); root.setLayout(new GridLayout(2, false));
        providerList = new List(root, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL); providerList.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        Composite edit = new Composite(root, SWT.NONE); edit.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false)); edit.setLayout(new GridLayout(2, false));
        new Label(edit, SWT.NONE).setText("Name:"); name = field(edit); new Label(edit, SWT.NONE).setText("Command:"); command = field(edit); new Label(edit, SWT.NONE).setText("Arguments:"); arguments = field(edit);
        Button fresh = new Button(edit, SWT.PUSH); fresh.setText("New provider"); fresh.addListener(SWT.Selection, e -> { providerList.deselectAll(); name.setText(""); command.setText(""); arguments.setText(""); });
        Button save = new Button(edit, SWT.PUSH); save.setText("Add / update"); save.addListener(SWT.Selection, e -> saveProvider());
        Button select = new Button(edit, SWT.PUSH); select.setText("Set as default"); select.addListener(SWT.Selection, e -> { int i = providerList.getSelectionIndex(); if (i >= 0) { registry.select(registry.list().get(i).id()); refresh(); } });
        Button remove = new Button(edit, SWT.PUSH); remove.setText("Remove"); remove.addListener(SWT.Selection, e -> { int i = providerList.getSelectionIndex(); if (i >= 0) { registry.remove(registry.list().get(i).id()); refresh(); } });
        reviewFileChanges = new Button(root, SWT.CHECK);
        reviewFileChanges.setText("Review ACP file changes before applying them (Apply / Reject / Undo)");
        reviewFileChanges.setSelection(AcpPreferences.store().getBoolean(AcpPreferences.REVIEW_FILE_CHANGES));
        GridData reviewData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        reviewData.horizontalSpan = 2;
        reviewFileChanges.setLayoutData(reviewData);
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
        providerList.addListener(SWT.Selection, e -> loadSelected()); refresh(); return root;
    }
    private Text field(Composite parent) { Text t = new Text(parent, SWT.BORDER); t.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false)); return t; }
    private void refresh() { providerList.removeAll(); for (AgentProvider p : registry.list()) providerList.add(p.name() + (p.id().equals(registry.active().id()) ? " (active)" : "")); if (providerList.getItemCount() > 0) providerList.select(0); loadSelected(); }
    private void loadSelected() { int i = providerList.getSelectionIndex(); if (i >= 0) { AgentProvider p = registry.list().get(i); name.setText(p.name()); command.setText(p.command()); arguments.setText(p.arguments()); } }
    private void saveProvider() { int i = providerList.getSelectionIndex(); String id = i >= 0 ? registry.list().get(i).id() : name.getText().trim().toLowerCase().replaceAll("[^a-z0-9]+", "-"); AgentProvider p = new AgentProvider(id, name.getText(), command.getText(), arguments.getText()); if (i >= 0) registry.update(p); else registry.add(p); refresh(); }
    @Override public boolean performOk() {
        AcpPreferences.store().setValue(AcpPreferences.REVIEW_FILE_CHANGES, reviewFileChanges.getSelection());
        AcpPreferences.store().setValue(AcpPreferences.HIDE_AGENT_COMMANDS_IN_CHAT, hideAgentCommandsInChat.getSelection());
        AcpPreferences.store().setValue(AcpPreferences.DEBUG_ACP_MESSAGES, debugAcpMessages.getSelection()); return true;
    }
    @Override public void init(IWorkbench workbench) { }
}
