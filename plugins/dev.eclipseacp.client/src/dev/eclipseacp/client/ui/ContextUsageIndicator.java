package dev.eclipseacp.client.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleControlAdapter;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;

import dev.eclipseacp.client.agent.Usage;

/** Compact, colour-coded context-window meter for the composer footer. */
final class ContextUsageIndicator extends Canvas {
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
