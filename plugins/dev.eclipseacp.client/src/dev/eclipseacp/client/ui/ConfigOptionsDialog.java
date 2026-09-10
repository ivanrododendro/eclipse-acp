package dev.eclipseacp.client.ui;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;

import dev.eclipseacp.client.agent.ConfigOption;

/** SWT editor for protocol-neutral agent configuration values. */
final class ConfigOptionsDialog extends Dialog {
    private final List<ConfigOption> options;
    private final Map<ConfigOption, Editor> editors = new LinkedHashMap<>();
    private final Map<ConfigOption, Object> changedValues = new LinkedHashMap<>();

    ConfigOptionsDialog(Shell parentShell, List<ConfigOption> options) {
        super(parentShell);
        this.options = List.copyOf(options);
    }

    @Override protected void configureShell(Shell shell) { super.configureShell(shell); shell.setText("Agent options"); }

    @Override protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        ScrolledComposite scroller = new ScrolledComposite(area, SWT.V_SCROLL | SWT.H_SCROLL);
        scroller.setExpandHorizontal(true); scroller.setExpandVertical(true);
        scroller.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        Composite content = new Composite(scroller, SWT.NONE);
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 10; layout.marginHeight = 10; layout.verticalSpacing = 9;
        content.setLayout(layout);
        for (ConfigOption option : options) addOption(content, option);
        scroller.setContent(content); scroller.setMinSize(content.computeSize(SWT.DEFAULT, SWT.DEFAULT));
        return area;
    }

    @Override protected org.eclipse.swt.graphics.Point getInitialSize() { return new org.eclipse.swt.graphics.Point(620, 520); }

    private void addOption(Composite parent, ConfigOption option) {
        Label name = new Label(parent, SWT.NONE); name.setText(option.name());
        name.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        Editor editor = createEditor(parent, option);
        GridData data = editor.control().getLayoutData() instanceof GridData existing ? existing
                : new GridData(SWT.FILL, SWT.CENTER, true, false);
        data.horizontalAlignment = SWT.FILL; data.grabExcessHorizontalSpace = true;
        editor.control().setLayoutData(data); editors.put(option, editor);
        if (!option.description().isBlank()) {
            Label description = new Label(parent, SWT.WRAP); description.setText(option.description());
            GridData descriptionData = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
            descriptionData.widthHint = 440; description.setLayoutData(descriptionData);
        }
    }

    private static Editor createEditor(Composite parent, ConfigOption option) {
        Object value = option.value() == null ? null : option.value().value();
        if (!option.choices().isEmpty()) return choiceEditor(parent, option, value);
        if (value instanceof Boolean selected) {
            Button checkbox = new Button(parent, SWT.CHECK); checkbox.setSelection(selected);
            return new Editor(checkbox, () -> checkbox.getSelection());
        }
        if (value instanceof Number number && isIntegral(number) && inIntegerRange(number)) {
            Spinner spinner = new Spinner(parent, SWT.BORDER); spinner.setMinimum(Integer.MIN_VALUE); spinner.setMaximum(Integer.MAX_VALUE);
            spinner.setSelection(number.intValue()); return new Editor(spinner, spinner::getSelection);
        }
        Text text = new Text(parent, SWT.BORDER);
        text.setText(value == null ? "" : String.valueOf(value));
        if (value instanceof Number) return new Editor(text, () -> new BigDecimal(text.getText().trim()));
        return new Editor(text, text::getText);
    }

    private static Editor choiceEditor(Composite parent, ConfigOption option, Object current) {
        Combo combo = new Combo(parent, SWT.DROP_DOWN | SWT.READ_ONLY);
        String currentValue = current == null ? "" : String.valueOf(current);
        int selected = -1;
        for (ConfigOption.Choice choice : option.choices()) {
            combo.add(choice.label()); if (choice.value().equals(currentValue)) selected = combo.getItemCount() - 1;
        }
        if (selected >= 0) combo.select(selected); else if (combo.getItemCount() > 0) combo.select(0);
        return new Editor(combo, () -> {
            int index = combo.getSelectionIndex();
            return index < 0 ? null : choiceValue(option.choices().get(index).value(), current);
        });
    }

    @Override protected void okPressed() {
        changedValues.clear();
        try {
            for (Map.Entry<ConfigOption, Editor> entry : editors.entrySet()) {
                Object value = entry.getValue().value();
                Object current = entry.getKey().value() == null ? null : entry.getKey().value().value();
                if (!java.util.Objects.equals(current, value)) changedValues.put(entry.getKey(), value);
            }
        } catch (RuntimeException error) {
            MessageDialog.openError(getShell(), "Invalid option value", "Enter a valid value for this option. " + error.getMessage());
            return;
        }
        super.okPressed();
    }

    Map<ConfigOption, Object> changedValues() { return Map.copyOf(changedValues); }

    private static Object choiceValue(String choice, Object current) {
        if (current instanceof Boolean) return Boolean.parseBoolean(choice);
        if (current instanceof Number) try { return new BigDecimal(choice); } catch (NumberFormatException ignored) { }
        return choice;
    }
    private static boolean isIntegral(Number value) { return new BigDecimal(value.toString()).stripTrailingZeros().scale() <= 0; }
    private static boolean inIntegerRange(Number value) { BigDecimal number = new BigDecimal(value.toString()); return number.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) >= 0 && number.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0; }
    private record Editor(Control control, ValueReader reader) { Object value() { return reader.read(); } }
    @FunctionalInterface private interface ValueReader { Object read(); }
}
