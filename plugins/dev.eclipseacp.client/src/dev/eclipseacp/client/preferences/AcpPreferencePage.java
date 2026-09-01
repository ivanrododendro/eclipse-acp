package dev.eclipseacp.client.preferences;

import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

public final class AcpPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {
    public AcpPreferencePage() {
        super(GRID);
        setPreferenceStore(AcpPreferences.store());
        setDescription("Configure the local ACP agent launched by Eclipse.");
    }

    @Override
    protected void createFieldEditors() {
        addField(new StringFieldEditor(
                AcpPreferences.AGENT_NAME,
                "Agent name:",
                getFieldEditorParent()));
        addField(new StringFieldEditor(
                AcpPreferences.AGENT_COMMAND,
                "Command:",
                getFieldEditorParent()));
        addField(new StringFieldEditor(
                AcpPreferences.AGENT_ARGUMENTS,
                "Arguments:",
                getFieldEditorParent()));
    }

    @Override
    public void init(IWorkbench workbench) {
        // No workbench-specific initialization required.
    }
}
