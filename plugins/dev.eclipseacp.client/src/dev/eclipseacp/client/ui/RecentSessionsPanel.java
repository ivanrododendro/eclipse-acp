package dev.eclipseacp.client.ui;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleControlAdapter;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import dev.eclipseacp.client.agent.SessionInfo;

/** Presents recent chats and emits selection/close actions without owning agent lifecycle. */
final class RecentSessionsPanel {
    record RecentSession(String label, String updatedAt, ChatSessionModel open, SessionInfo saved) { }

    private final Composite control;
    private final Consumer<RecentSession> open;
    private final List<Canvas> recentButtons;
    private final Canvas viewAllButton;
    private List<RecentSession> recentSessions = List.of();
    private Shell allSessionsPopup;
    private Table allSessionsTable;
    private Text allSessionsFilter;
    private TableColumn allSessionsTitleColumn;
    private TableColumn allSessionsAgeColumn;

    RecentSessionsPanel(Composite parent, int visibleCount, Consumer<RecentSession> open,
            Consumer<ChatSessionModel> onClose) {
        this.open = open;
        control = new Composite(parent, SWT.NONE);
        control.setBackground(parent.getBackground());
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = layout.marginHeight = 0;
        layout.verticalSpacing = 1;
        control.setLayout(layout);
        List<Canvas> buttons = new ArrayList<>();
        for (int i = 0; i < visibleCount; i++) {
            final int index = i;
            Canvas row = createTextAction(control,
                    () -> index < recentSessions.size() ? recentSessions.get(index).label() : "",
                    () -> index < recentSessions.size()
                            ? SessionAge.format(recentSessions.get(index).updatedAt(), Instant.now()) : "",
                    () -> openRecent(index));
            Menu menu = new Menu(row);
            MenuItem close = new MenuItem(menu, SWT.PUSH);
            close.setText("Close chat");
            close.addListener(SWT.Selection, ignored -> {
                if (index < recentSessions.size() && recentSessions.get(index).open() != null) {
                    onClose.accept(recentSessions.get(index).open());
                }
            });
            menu.addListener(SWT.Show, ignored -> close.setEnabled(index < recentSessions.size()
                    && recentSessions.get(index).open() != null));
            row.setMenu(menu);
            buttons.add(row);
        }
        recentButtons = List.copyOf(buttons);
        viewAllButton = createTextAction(control, () -> "View all (" + recentSessions.size() + ")", () -> "",
                this::showAllSessions);
        viewAllButton.setToolTipText("Show all available chats");
        control.addListener(SWT.Resize, ignored -> positionAllSessionsPopup());
        control.addDisposeListener(ignored -> closeAllSessionsPopup());
    }

    Composite control() { return control; }

    void update(ChatSessionModel session) {
        if (control.isDisposed()) return;
        closeAllSessionsPopup();
        recentSessions = entriesFor(session);
        boolean canRestore = session != null && !session.isBusy()
                && session.canRestoreSavedSessions();
        for (int i = 0; i < recentButtons.size(); i++) {
            boolean visible = i < recentSessions.size();
            Canvas button = recentButtons.get(i);
            button.setEnabled(false);
            if (visible) {
                RecentSession entry = recentSessions.get(i);
                button.setEnabled(entry.open() != null || canRestore);
                button.setToolTipText((entry.open() == null ? "Restore chat: " : "Open chat: ") + entry.label());
            }
            showControl(button, visible);
            button.redraw();
        }
        boolean showViewAll = shouldShowViewAll(recentSessions.size(), recentButtons.size());
        viewAllButton.setEnabled(showViewAll);
        showControl(viewAllButton, showViewAll);
        viewAllButton.redraw();
        viewAllButton.getParent().layout(true, true);
    }

    static List<RecentSession> entriesFor(ChatSessionModel session) {
        List<RecentSession> entries = new ArrayList<>();
        if (session != null) {
            List<SessionInfo> saved = session.savedSessions.stream()
                    .sorted(Comparator.comparing((SessionInfo info) -> info.updatedAt() == null ? "" : info.updatedAt()).reversed())
                    .toList();
            boolean activeIsSaved = session.sessionId != null
                    && saved.stream().anyMatch(info -> session.sessionId.equals(info.id()));
            if (!activeIsSaved && !"New session".equals(session.sessionName)) {
                entries.add(new RecentSession(chatTitle(session.sessionName), null, session, null));
            }
            for (SessionInfo info : saved) {
                boolean active = info.id().equals(session.sessionId);
                if (active && info.title().isBlank()) continue;
                entries.add(new RecentSession(chatTitle(info.title().isBlank() ? info.id() : info.title()), info.updatedAt(),
                        active && session.isConnected() ? session : null,
                        active && session.isConnected() ? null : info));
            }
        }
        return List.copyOf(entries);
    }

    static boolean shouldShowViewAll(int sessionCount, int visibleCount) {
        return sessionCount > visibleCount;
    }

    private Canvas createTextAction(Composite parent, Supplier<String> label, Supplier<String> trailingLabel,
            Runnable action) {
        Canvas control = new Canvas(parent, SWT.DOUBLE_BUFFERED);
        control.setBackground(parent.getBackground());
        control.setForeground(parent.getDisplay().getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
        control.setCursor(parent.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
        data.heightHint = 18;
        control.setLayoutData(data);
        control.addPaintListener(event -> {
            String text = label.get();
            String trailingText = trailingLabel.get();
            event.gc.setForeground(control.getEnabled() ? control.getForeground()
                    : control.getDisplay().getSystemColor(SWT.COLOR_WIDGET_DISABLED_FOREGROUND));
            int trailingWidth = trailingText.isEmpty() ? 0 : event.gc.textExtent(trailingText).x;
            int trailingGap = trailingText.isEmpty() ? 0 : 10;
            int availableWidth = Math.max(0, control.getClientArea().width - 10 - trailingWidth - trailingGap);
            String visibleText = ellipsize(event.gc, chatTitle(text), availableWidth);
            int textHeight = event.gc.textExtent(visibleText).y;
            int y = Math.max(0, (control.getClientArea().height - textHeight) / 2);
            event.gc.drawText(visibleText, 5, y, true);
            if (!trailingText.isEmpty()) {
                event.gc.drawText(trailingText, control.getClientArea().width - 5 - trailingWidth, y, true);
            }
        });
        control.addListener(SWT.MouseDown, event -> { if (event.button == 1) control.setFocus(); });
        control.addListener(SWT.MouseUp, event -> {
            if (control.getEnabled() && event.button == 1 && control.getClientArea().contains(event.x, event.y))
                action.run();
        });
        control.addListener(SWT.KeyDown, event -> {
            if (control.getEnabled() && (event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR
                    || event.character == ' ')) action.run();
        });
        control.getAccessible().addAccessibleListener(new AccessibleAdapter() {
            @Override public void getName(AccessibleEvent event) {
                String trailingText = trailingLabel.get();
                event.result = trailingText.isEmpty() ? label.get() : label.get() + ", " + trailingText;
            }
        });
        control.getAccessible().addAccessibleControlListener(new AccessibleControlAdapter() {
            @Override public void getRole(AccessibleControlEvent event) { event.detail = ACC.ROLE_PUSHBUTTON; }
        });
        return control;
    }

    /** Normalizes agent-provided titles so SWT never lays out a recent-chat row on multiple lines. */
    static String chatTitle(String title) {
        return title == null ? "" : title.replaceAll("\\R", " ").replaceAll("\\s+", " ").trim();
    }

    /** Fits a single-line label to its Canvas using the active platform font metrics. */
    private static String ellipsize(GC gc, String text, int availableWidth) {
        if (availableWidth <= 0 || text.isEmpty() || gc.textExtent(text).x <= availableWidth) return text;
        String ellipsis = "…";
        int ellipsisWidth = gc.textExtent(ellipsis).x;
        if (ellipsisWidth > availableWidth) return "";
        int end = text.length();
        while (end > 0 && gc.textExtent(text.substring(0, end)).x + ellipsisWidth > availableWidth) {
            end = text.offsetByCodePoints(end, -1);
        }
        return text.substring(0, end) + ellipsis;
    }

    private void openRecent(int index) {
        if (index >= 0 && index < recentSessions.size()) open.accept(recentSessions.get(index));
    }

    private void showAllSessions() {
        if (allSessionsPopup != null && !allSessionsPopup.isDisposed()) {
            closeAllSessionsPopup();
            return;
        }
        allSessionsPopup = new Shell(control.getShell(), SWT.ON_TOP | SWT.TOOL);
        GridLayout popupLayout = new GridLayout(1, false);
        popupLayout.marginWidth = popupLayout.marginHeight = 0;
        popupLayout.verticalSpacing = 0;
        allSessionsPopup.setLayout(popupLayout);
        allSessionsFilter = new Text(allSessionsPopup, SWT.SINGLE | SWT.SEARCH | SWT.ICON_SEARCH | SWT.CANCEL);
        allSessionsFilter.setMessage("Filter chats");
        allSessionsFilter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        allSessionsTable = new Table(allSessionsPopup, SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER | SWT.V_SCROLL);
        allSessionsTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        allSessionsTitleColumn = new TableColumn(allSessionsTable, SWT.LEFT);
        allSessionsAgeColumn = new TableColumn(allSessionsTable, SWT.RIGHT);
        allSessionsTable.addListener(SWT.Resize, ignored -> layoutAllSessionsColumns());
        allSessionsFilter.addModifyListener(ignored -> refreshAllSessionsResults());
        allSessionsFilter.addListener(SWT.KeyDown, event -> {
            if (event.keyCode == SWT.ARROW_DOWN && allSessionsTable.getItemCount() > 0) {
                allSessionsTable.setFocus();
                event.doit = false;
            } else if (event.keyCode == SWT.ESC) {
                closeAllSessionsPopup();
                viewAllButton.setFocus();
                event.doit = false;
            } else if ((event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR)
                    && allSessionsTable.getSelectionCount() > 0) {
                openSelectedSession();
                event.doit = false;
            }
        });
        allSessionsFilter.addListener(SWT.FocusOut, ignored -> closeAllSessionsPopupAfterFocusChange());
        refreshAllSessionsResults();
        allSessionsTable.addListener(SWT.DefaultSelection, ignored -> openSelectedSession());
        allSessionsTable.addListener(SWT.KeyDown, event -> {
            if (event.keyCode == SWT.ESC) {
                closeAllSessionsPopup();
                viewAllButton.setFocus();
                event.doit = false;
            }
        });
        allSessionsTable.addListener(SWT.FocusOut, ignored -> closeAllSessionsPopupAfterFocusChange());
        allSessionsPopup.addListener(SWT.Deactivate, ignored -> closeAllSessionsPopup());
        positionAllSessionsPopup();
        allSessionsPopup.setVisible(true);
        allSessionsFilter.setFocus();
    }

    private void refreshAllSessionsResults() {
        if (allSessionsTable == null || allSessionsTable.isDisposed()) return;
        allSessionsTable.removeAll();
        String query = allSessionsFilter.getText().toLowerCase(Locale.ROOT);
        for (RecentSession session : recentSessions) {
            if (!session.label().toLowerCase(Locale.ROOT).contains(query)) continue;
            TableItem item = new TableItem(allSessionsTable, SWT.NONE);
            String age = SessionAge.format(session.updatedAt(), Instant.now());
            item.setText(0, session.label());
            item.setText(1, age);
            item.setData(session);
        }
        if (allSessionsTable.getItemCount() > 0) allSessionsTable.select(0);
        layoutAllSessionsColumns();
        positionAllSessionsPopup();
    }

    private void layoutAllSessionsColumns() {
        if (allSessionsTable == null || allSessionsTable.isDisposed()) return;
        GC gc = new GC(allSessionsTable);
        int ageWidth = 0;
        for (TableItem item : allSessionsTable.getItems()) {
            ageWidth = Math.max(ageWidth, gc.textExtent(item.getText(1)).x);
        }
        gc.dispose();
        ageWidth += 10;
        allSessionsAgeColumn.setWidth(ageWidth);
        allSessionsTitleColumn.setWidth(Math.max(0, allSessionsTable.getClientArea().width - ageWidth));
    }

    private void positionAllSessionsPopup() {
        if (allSessionsPopup == null || allSessionsPopup.isDisposed() || viewAllButton.isDisposed()) return;
        Point origin = viewAllButton.toDisplay(0, viewAllButton.getSize().y);
        int width = control.getClientArea().width;
        int belowButton = viewAllButton.getBounds().y + viewAllButton.getSize().y;
        int filterHeight = allSessionsFilter.computeSize(SWT.DEFAULT, SWT.DEFAULT).y;
        int availableHeight = Math.max(filterHeight + allSessionsTable.getItemHeight() + 4,
                control.getClientArea().height - belowButton);
        int contentHeight = filterHeight + Math.max(1, allSessionsTable.getItemCount())
                * allSessionsTable.getItemHeight() + 4;
        int height = Math.min(contentHeight, availableHeight);
        allSessionsPopup.setBounds(origin.x, origin.y, width, height);
    }

    private void closeAllSessionsPopupAfterFocusChange() {
        control.getDisplay().asyncExec(() -> {
            if (allSessionsPopup == null || allSessionsPopup.isDisposed()) return;
            Control focus = control.getDisplay().getFocusControl();
            if (focus != allSessionsFilter && focus != allSessionsTable) closeAllSessionsPopup();
        });
    }

    private void openSelectedSession() {
        if (allSessionsTable == null || allSessionsTable.isDisposed() || allSessionsTable.getSelectionCount() == 0) return;
        RecentSession selected = (RecentSession) allSessionsTable.getSelection()[0].getData();
        closeAllSessionsPopup();
        open.accept(selected);
    }

    private void closeAllSessionsPopup() {
        if (allSessionsPopup != null && !allSessionsPopup.isDisposed()) allSessionsPopup.dispose();
        allSessionsPopup = null;
        allSessionsTable = null;
        allSessionsFilter = null;
        allSessionsTitleColumn = null;
        allSessionsAgeColumn = null;
    }

    private static void showControl(Control control, boolean visible) {
        control.setVisible(visible);
        GridData data = control.getLayoutData() instanceof GridData existing ? existing : new GridData();
        data.exclude = !visible;
        control.setLayoutData(data);
    }
}
