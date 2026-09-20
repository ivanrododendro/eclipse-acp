package dev.eclipseacp.client.ui;

import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.LocationAdapter;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.IWorkbenchPage;

import com.google.gson.Gson;

/** Owns the transcript browser, incremental rendering, file links and text selection. */
final class ChatTranscript {
    private final Browser transcript;
    private final BrowserFunction transcriptSelectionBridge;
    private final Supplier<ChatSessionModel> activeSession;
    private final String chatFontFamily;
    private final int chatFontSizePoints;
    private String transcriptSelection = "";

    ChatTranscript(Composite parent, IWorkbenchPage page, Supplier<ChatSessionModel> activeSession,
            Consumer<String> newSession, String fontFamily, int fontSizePoints) {
        this.activeSession = activeSession;
        this.chatFontFamily = fontFamily;
        this.chatFontSizePoints = fontSizePoints;
        transcript = new Browser(parent, SWT.NONE);
        transcript.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        transcriptSelectionBridge = new BrowserFunction(transcript, "__acpRecordTranscriptSelection") {
            @Override public Object function(Object[] arguments) {
                transcriptSelection = arguments.length == 0 || arguments[0] == null ? "" : arguments[0].toString();
                return null;
            }
        };
        Menu menu = new Menu(transcript);
        MenuItem copy = new MenuItem(menu, SWT.PUSH);
        copy.setText("Copy and Paste in a New Session");
        copy.addListener(SWT.Selection, ignored -> {
            ChatSessionModel session = activeSession.get();
            if (!transcriptSelection.isBlank() && session != null && session.isConnected() && !session.isBusy()) {
                newSession.accept(transcriptSelection);
            }
            clearTranscriptSelection();
        });
        menu.addListener(SWT.Show, ignored -> {
            ChatSessionModel session = activeSession.get();
            copy.setEnabled(!transcriptSelection.isBlank() && session != null
                    && session.isConnected() && !session.isBusy());
        });
        transcript.setMenu(menu);
        transcript.addLocationListener(new LocationAdapter() {
            @Override public void changing(LocationEvent event) {
                ChatSessionModel session = activeSession.get();
                if (session != null && WorkspaceFileOpener.open(page, session.project, event.location)) {
                    event.doit = false;
                }
            }
        });
        transcript.addProgressListener(new ProgressAdapter() {
            @Override public void completed(ProgressEvent event) {
                replaceContent();
                installTranscriptSelectionTracking();
                scrollTranscriptToBottom();
            }
        });
        render();
    }

    boolean isDisposed() {
        return transcript.isDisposed();
    }

    void render() {
        if (isDisposed()) return;
        transcriptSelection = "";
        transcript.setText(chatDocument());
        scrollTranscriptToBottom();
    }

    void update() {
        if (isDisposed()) return;
        if (!replaceContent()) render();
    }

    private boolean replaceContent() {
        String document = new Gson().toJson(chatDocument());
        // Keep the document alive during streaming and follow each agent chunk.
        return transcript.execute("if(document.querySelector('main')){"
                + "var next=new DOMParser().parseFromString(" + document + ", 'text/html');"
                + "document.querySelector('main').innerHTML=next.querySelector('main').innerHTML;"
                + "window.scrollTo(0,Math.max(document.body.scrollHeight,document.documentElement.scrollHeight));}");
    }

    private String chatDocument() {
        ChatSessionModel session = activeSession.get();
        return GfmRenderer.document(session == null ? "" : session.transcriptMarkdown.toString(),
                chatFontFamily, chatFontSizePoints, session == null ? null : session.fileLinks::hrefFor);
    }

    private void installTranscriptSelectionTracking() {
        if (transcript == null || transcript.isDisposed()) return;
        transcript.execute("(() => {"
                + "if (window.__acpSelectionTrackingInstalled) return;"
                + "window.__acpSelectionTrackingInstalled = true;"
                + "const publishSelection = text => {"
                + "if (typeof window.__acpRecordTranscriptSelection === 'function') {"
                + "window.__acpRecordTranscriptSelection(text || '');"
                + "}"
                + "};"
                + "const rememberSelection = () => {"
                + "const text = window.getSelection ? window.getSelection().toString() : '';"
                + "if (!text.trim()) return;"
                + "publishSelection(text);"
                + "};"
                + "document.addEventListener('selectionchange', rememberSelection);"
                + "document.addEventListener('contextmenu', rememberSelection, true);"
                + "document.addEventListener('mouseup', rememberSelection, true);"
                + "document.addEventListener('keyup', rememberSelection, true);"
                + "document.addEventListener('mousedown', event => {"
                + "if (event.button === 0) publishSelection('');"
                + "}, true);"
                + "})()");
    }

    private void clearTranscriptSelection() {
        transcriptSelection = "";
        if (transcript == null || transcript.isDisposed()) return;
        transcript.execute("if (window.getSelection) window.getSelection().removeAllRanges();");
    }

    private void scrollTranscriptToBottom() {
        if (transcript == null || transcript.isDisposed()) {
            return;
        }
        transcript.execute("window.scrollTo(0, Math.max(document.body.scrollHeight, document.documentElement.scrollHeight));");
    }

    void dispose() {
        transcriptSelectionBridge.dispose();
    }
}
