package dev.eclipseacp.client.ui;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

/** Keyboard-first, non-modal palette for commands advertised by an ACP agent. */
final class SlashCommandCompletion {
    private static final int MAX_RESULTS = 6;
    private final Text prompt;
    private final Supplier<Map<String, String>> commands;
    private Shell popup;
    private Table results;

    SlashCommandCompletion(Text prompt, Supplier<Map<String, String>> commands) {
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
    void close() { if (isOpen()) popup.dispose(); popup = null; results = null; }

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
}
