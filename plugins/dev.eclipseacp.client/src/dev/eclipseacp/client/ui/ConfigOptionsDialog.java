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

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import dev.eclipseacp.client.agent.ConfigOption;

/** A session-scoped editor for the configuration options advertised by an ACP agent. */
final class ConfigOptionsDialog extends Dialog {
    private final List<ConfigOption> options;
    private final Map<ConfigOption, Editor> editors = new LinkedHashMap<>();
    private final Map<ConfigOption, JsonElement> changedValues = new LinkedHashMap<>();

    ConfigOptionsDialog(Shell parentShell, List<ConfigOption> options) {
        super(parentShell);
        this.options = List.copyOf(options);
    }

    @Override protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Agent options");
    }

    @Override protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        ScrolledComposite scroller = new ScrolledComposite(area, SWT.V_SCROLL | SWT.H_SCROLL);
        scroller.setExpandHorizontal(true);
        scroller.setExpandVertical(true);
        scroller.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite content = new Composite(scroller, SWT.NONE);
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 10;
        layout.marginHeight = 10;
        layout.verticalSpacing = 9;
        content.setLayout(layout);
        for (ConfigOption option : options) addOption(content, option);
        scroller.setContent(content);
        scroller.setMinSize(content.computeSize(SWT.DEFAULT, SWT.DEFAULT));
        return area;
    }

    @Override protected org.eclipse.swt.graphics.Point getInitialSize() {
        return new org.eclipse.swt.graphics.Point(620, 520);
    }

    private void addOption(Composite parent, ConfigOption option) {
        Label name = new Label(parent, SWT.NONE);
        name.setText(option.name());
        name.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));

        Editor editor = createEditor(parent, option);
        GridData editorData = editor.control().getLayoutData() instanceof GridData existing ? existing
                : new GridData(SWT.FILL, SWT.CENTER, true, false);
        editorData.horizontalAlignment = SWT.FILL;
        editorData.grabExcessHorizontalSpace = true;
        editor.control().setLayoutData(editorData);
        editors.put(option, editor);

        if (!option.description().isBlank()) {
            Label description = new Label(parent, SWT.WRAP);
            description.setText(option.description());
            GridData data = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
            data.widthHint = 440;
            description.setLayoutData(data);
        }
    }

    private static Editor createEditor(Composite parent, ConfigOption option) {
        JsonElement value = json(option.value());
        if (!option.choices().isEmpty()) return choiceEditor(parent, option, value);
        if (isBoolean(value)) {
            Button checkbox = new Button(parent, SWT.CHECK);
            checkbox.setSelection(value.getAsBoolean());
            return new Editor(checkbox, () -> new JsonPrimitive(checkbox.getSelection()));
        }
        if (isIntegral(value) && value.getAsBigDecimal().compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) >= 0
                && value.getAsBigDecimal().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0) {
            Spinner spinner = new Spinner(parent, SWT.BORDER);
            spinner.setMinimum(Integer.MIN_VALUE);
            spinner.setMaximum(Integer.MAX_VALUE);
            spinner.setSelection(value.getAsInt());
            return new Editor(spinner, () -> new JsonPrimitive(spinner.getSelection()));
        }
        if (isNumber(value)) return numberEditor(parent, value);
        if (value != null && (value.isJsonArray() || value.isJsonObject() || value.isJsonNull())) return jsonEditor(parent, value);
        Text text = new Text(parent, SWT.BORDER);
        text.setText(value == null || value.isJsonNull() ? "" : value.getAsString());
        return new Editor(text, () -> new JsonPrimitive(text.getText()));
    }

    private static Editor choiceEditor(Composite parent, ConfigOption option, JsonElement current) {
        Combo combo = new Combo(parent, SWT.DROP_DOWN | SWT.READ_ONLY);
        String currentValue = primitiveText(current);
        int selected = -1;
        for (ConfigOption.Choice choice : option.choices()) {
            combo.add(choice.label());
            if (choice.value().equals(currentValue)) selected = combo.getItemCount() - 1;
        }
        if (selected >= 0) combo.select(selected);
        else if (combo.getItemCount() > 0) combo.select(0);
        return new Editor(combo, () -> {
            int index = combo.getSelectionIndex();
            return index < 0 ? JsonNull.INSTANCE : choiceValue(option.choices().get(index).value(), current);
        });
    }

    private static Editor numberEditor(Composite parent, JsonElement value) {
        Text text = new Text(parent, SWT.BORDER);
        text.setText(value.getAsString());
        return new Editor(text, () -> new JsonPrimitive(new BigDecimal(text.getText().trim())));
    }

    private static Editor jsonEditor(Composite parent, JsonElement value) {
        Text text = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        text.setText(value.toString());
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
        data.heightHint = 72;
        text.setLayoutData(data);
        return new Editor(text, () -> JsonParser.parseString(text.getText()));
    }

    @Override protected void okPressed() {
        changedValues.clear();
        try {
            for (Map.Entry<ConfigOption, Editor> entry : editors.entrySet()) {
                JsonElement newValue = entry.getValue().value();
                JsonElement oldValue = json(entry.getKey().value());
                if (!sameValue(oldValue, newValue)) changedValues.put(entry.getKey(), newValue);
            }
        } catch (RuntimeException error) {
            MessageDialog.openError(getShell(), "Invalid option value", "Enter a valid value for this option. " + error.getMessage());
            return;
        }
        super.okPressed();
    }

    Map<ConfigOption, JsonElement> changedValues() {
        return Map.copyOf(changedValues);
    }

    private static boolean sameValue(JsonElement first, JsonElement second) {
        return first == null ? second == null || second.isJsonNull() : first.equals(second);
    }

    private static JsonElement json(Object value) {
        return value instanceof JsonElement element ? element : new com.google.gson.Gson().toJsonTree(value);
    }

    private static boolean isBoolean(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
    }

    private static boolean isNumber(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
    }

    private static boolean isIntegral(JsonElement value) {
        return isNumber(value) && value.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
    }

    private static String primitiveText(JsonElement value) {
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static JsonElement choiceValue(String choice, JsonElement current) {
        if (isBoolean(current)) return new JsonPrimitive(Boolean.parseBoolean(choice));
        if (isNumber(current)) {
            try { return new JsonPrimitive(new BigDecimal(choice)); }
            catch (NumberFormatException ignored) { /* Providers normally use values with the same type. */ }
        }
        return new JsonPrimitive(choice);
    }

    private record Editor(Control control, ValueReader reader) {
        JsonElement value() { return reader.read(); }
    }

    @FunctionalInterface private interface ValueReader {
        JsonElement read();
    }
}
