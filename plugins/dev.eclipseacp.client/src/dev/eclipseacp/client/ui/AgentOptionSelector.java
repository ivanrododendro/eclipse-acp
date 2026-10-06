package dev.eclipseacp.client.ui;

import java.util.function.BiConsumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;

import dev.eclipseacp.client.agent.ConfigOption;

/** Owns one agent-advertised choice widget; session actions are supplied by its caller. */
final class AgentOptionSelector {
    private final CCombo selector;
    private final String defaultTooltip;
    private final boolean selectFirstWhenUnknown;
    private ConfigOption option;
    private boolean editable;

    AgentOptionSelector(Composite parent, Color background, String name, boolean selectFirstWhenUnknown,
            BiConsumer<ConfigOption, ConfigOption.Choice> changed) {
        this.defaultTooltip = name + " for the active ACP session";
        this.selectFirstWhenUnknown = selectFirstWhenUnknown;
        selector = new CCombo(parent, SWT.READ_ONLY | SWT.FLAT);
        selector.setBackground(background);
        selector.setToolTipText(defaultTooltip);
        selector.setEnabled(false);
        selector.addListener(SWT.Selection, ignored -> {
            int selected = selector.getSelectionIndex();
            if (!editable || option == null || selected < 0 || selected >= option.choices().size()) return;
            selector.setEnabled(false);
            changed.accept(option, option.choices().get(selected));
        });
    }

    void reset() {
        update(null, false);
        selector.setToolTipText(defaultTooltip);
    }

    void update(ConfigOption option, boolean editable) {
        if (selector.isDisposed()) return;
        this.option = option;
        this.editable = editable;
        selector.removeAll();
        if (option == null) {
            selector.setEnabled(false);
            selector.setToolTipText(selectFirstWhenUnknown
                    ? "The ACP agent has not advertised a model selector" : defaultTooltip);
        } else {
            String current = AgentConfigOptions.text(option.value());
            int selected = -1;
            for (ConfigOption.Choice choice : option.choices()) {
                selector.add(choice.label());
                if (choice.value().equals(current)) selected = selector.getItemCount() - 1;
            }
            if (selected >= 0) selector.select(selected);
            else if (selectFirstWhenUnknown && selector.getItemCount() > 0) selector.select(0);
            selector.setEnabled(editable);
            selector.setToolTipText(option.description().isBlank() ? defaultTooltip : option.description());
        }
        boolean visible = selector.getItemCount() > 0;
        selector.setVisible(visible);
        GridData data = selector.getLayoutData() instanceof GridData existing ? existing : new GridData();
        data.exclude = !visible;
        selector.setLayoutData(data);
    }
}
