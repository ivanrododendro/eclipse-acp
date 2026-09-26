package dev.eclipseacp.client.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleControlAdapter;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;

/** A borderless, accessible action control for icon-only commands. */
final class IconButton extends Canvas {
    private final Runnable action;
    private final Image image;

    IconButton(Composite parent, Image image, String tooltip, Runnable action) {
        super(parent, SWT.DOUBLE_BUFFERED);
        this.image = image;
        this.action = action;
        setBackground(parent.getBackground());
        setToolTipText(tooltip);
        setLayoutData(new GridData(24, 24));
        setCursor(getDisplay().getSystemCursor(SWT.CURSOR_HAND));

        addPaintListener(event -> {
            var imageBounds = image.getBounds();
            var area = getClientArea();
            int x = area.x + (area.width - imageBounds.width) / 2;
            int y = area.y + (area.height - imageBounds.height) / 2;
            event.gc.setAlpha(getEnabled() ? 255 : 90);
            event.gc.drawImage(image, x, y);
        });
        addListener(SWT.MouseUp, event -> {
            if (getEnabled() && getClientArea().contains(event.x, event.y)) action.run();
        });
        addListener(SWT.KeyDown, event -> {
            if (getEnabled() && (event.character == ' ' || event.keyCode == SWT.CR)) action.run();
        });
        configureAccessibility(tooltip);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        redraw();
    }

    private void configureAccessibility(String name) {
        getAccessible().addAccessibleListener(new AccessibleAdapter() {
            @Override public void getName(AccessibleEvent event) { event.result = name; }
        });
        getAccessible().addAccessibleControlListener(new AccessibleControlAdapter() {
            @Override public void getRole(AccessibleControlEvent event) { event.detail = ACC.ROLE_PUSHBUTTON; }
            @Override public void getState(AccessibleControlEvent event) {
                event.detail = getEnabled() ? ACC.STATE_FOCUSABLE : ACC.STATE_DISABLED;
            }
        });
    }
}
