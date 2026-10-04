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
    private final BrowserFunction transcriptZoomBridge;
    private final Supplier<ChatSessionModel> activeSession;
    private final String chatFontFamily;
    private final int chatFontSizePoints;
    private final String chatBackgroundColor;
    private String transcriptSelection = "";
    private ChatSessionModel renderedSession;
    private String renderedMarkdown = "";
    private int renderedBoundary;

    ChatTranscript(Composite parent, IWorkbenchPage page, Supplier<ChatSessionModel> activeSession,
            Consumer<String> newSession, String fontFamily, int fontSizePoints) {
        this.activeSession = activeSession;
        this.chatFontFamily = fontFamily;
        this.chatFontSizePoints = fontSizePoints;
        var backgroundRgb = parent.getBackground().getRGB();
        this.chatBackgroundColor = "#%02x%02x%02x".formatted(
                backgroundRgb.red, backgroundRgb.green, backgroundRgb.blue);
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
        new MenuItem(menu, SWT.SEPARATOR);
        MenuItem zoomIn = new MenuItem(menu, SWT.PUSH);
        zoomIn.setText("Zoom in\tCtrl/Cmd+");
        zoomIn.addListener(SWT.Selection, ignored -> changeZoom(10));
        MenuItem zoomOut = new MenuItem(menu, SWT.PUSH);
        zoomOut.setText("Zoom out\tCtrl/Cmd-");
        zoomOut.addListener(SWT.Selection, ignored -> changeZoom(-10));
        MenuItem resetZoom = new MenuItem(menu, SWT.PUSH);
        resetZoom.setText("Reset zoom\tCtrl/Cmd0");
        resetZoom.addListener(SWT.Selection, ignored -> setZoom(ProjectChatZoom.DEFAULT));
        menu.addListener(SWT.Show, ignored -> {
            ChatSessionModel session = activeSession.get();
            copy.setEnabled(!transcriptSelection.isBlank() && session != null
                    && session.isConnected() && !session.isBusy());
            boolean canZoom = session != null;
            int zoom = canZoom ? ProjectChatZoom.load(session.project) : ProjectChatZoom.DEFAULT;
            zoomIn.setEnabled(canZoom && zoom < ProjectChatZoom.MAXIMUM);
            zoomOut.setEnabled(canZoom && zoom > ProjectChatZoom.MINIMUM);
            resetZoom.setEnabled(canZoom && zoom != ProjectChatZoom.DEFAULT);
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
                installTranscriptSelectionTracking();
                installZoomShortcuts();
                scrollTranscriptToBottom();
            }
        });
        transcriptZoomBridge = new BrowserFunction(transcript, "__acpSetTranscriptZoom") {
            @Override public Object function(Object[] arguments) {
                if (arguments.length == 1 && arguments[0] instanceof Number value) {
                    if (value.intValue() == 0) setZoom(ProjectChatZoom.DEFAULT);
                    else changeZoom(value.intValue());
                }
                return null;
            }
        };
        render();
    }

    boolean isDisposed() {
        return transcript.isDisposed();
    }

    void render() {
        if (isDisposed()) return;
        transcriptSelection = "";
        transcript.setEnabled(activeSession.get() != null);
        if (activeSession.get() == null) {
            // Clear the currently displayed DOM before the asynchronous page load completes.
            transcript.execute("if(document.body) document.body.replaceChildren();");
        }
        TranscriptSnapshot snapshot = snapshot();
        renderedSession = snapshot.session();
        renderedMarkdown = snapshot.markdown();
        renderedBoundary = snapshot.boundary();
        transcript.setText(chatDocument(snapshot));
        scrollTranscriptToBottom();
    }

    void update() {
        if (isDisposed()) return;
        TranscriptSnapshot next = snapshot();
        if (next.session() != renderedSession || !next.markdown().startsWith(renderedMarkdown)) {
            render();
            return;
        }
        if (next.markdown().equals(renderedMarkdown)) return;
        boolean updated;
        if (next.boundary() == renderedBoundary) {
            updated = replaceLive(next.liveMarkdown());
        } else if (next.boundary() >= renderedMarkdown.length()) {
            String stableDelta = next.markdown().substring(renderedMarkdown.length(), next.boundary());
            updated = advanceLive(stableDelta, next.liveMarkdown());
        } else {
            updated = false;
        }
        if (!updated) {
            render();
            return;
        }
        renderedMarkdown = next.markdown();
        renderedBoundary = next.boundary();
    }

    private boolean replaceLive(String markdown) {
        String html = new Gson().toJson(conversationPart(markdown));
        return transcript.execute("(() => {const live=document.getElementById('transcript-live');if(!live)return false;"
                + "const follow=window.innerHeight+window.scrollY>=document.documentElement.scrollHeight-24;"
                + "live.innerHTML=" + html + ";"
                + "if(follow)window.scrollTo(0,Math.max(document.body.scrollHeight,document.documentElement.scrollHeight));"
                + "return true})()");
    }

    private boolean advanceLive(String stableDelta, String liveMarkdown) {
        String stableHtml = new Gson().toJson(conversationPart(stableDelta));
        String liveHtml = new Gson().toJson(conversationPart(liveMarkdown));
        return transcript.execute("(() => {const stable=document.getElementById('transcript-stable');"
                + "const live=document.getElementById('transcript-live');if(!stable||!live)return false;"
                + "const follow=window.innerHeight+window.scrollY>=document.documentElement.scrollHeight-24;"
                + "live.removeAttribute('id');stable.appendChild(live);"
                + "const delta=document.createElement('div');delta.className='transcript-part';delta.innerHTML="
                + stableHtml + ";stable.appendChild(delta);"
                + "const next=document.createElement('div');next.id='transcript-live';next.className='transcript-part';"
                + "next.innerHTML=" + liveHtml + ";stable.after(next);"
                + "if(follow)window.scrollTo(0,Math.max(document.body.scrollHeight,document.documentElement.scrollHeight));"
                + "return true})()");
    }

    private String conversationPart(String markdown) {
        ChatSessionModel session = activeSession.get();
        return GfmRenderer.conversationPart(markdown, session == null ? null : session.fileLinks::hrefFor);
    }

    private String chatDocument(TranscriptSnapshot snapshot) {
        ChatSessionModel session = snapshot.session();
        return GfmRenderer.document(snapshot.stableMarkdown(), snapshot.liveMarkdown(),
                chatFontFamily, scaledFontSize(session), session == null ? null : session.fileLinks::hrefFor,
                chatBackgroundColor);
    }

    private TranscriptSnapshot snapshot() {
        ChatSessionModel session = activeSession.get();
        String markdown = session == null ? "" : session.transcriptMarkdown.toString();
        int boundary = session == null ? 0 : Math.max(0, Math.min(session.liveMessageStart, markdown.length()));
        return new TranscriptSnapshot(session, markdown, boundary);
    }

    private int scaledFontSize(ChatSessionModel session) {
        int zoom = session == null ? ProjectChatZoom.DEFAULT : ProjectChatZoom.load(session.project);
        return Math.round(chatFontSizePoints * zoom / 100f);
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

    private void installZoomShortcuts() {
        if (transcript == null || transcript.isDisposed()) return;
        transcript.execute("(() => {"
                + "if (window.__acpZoomShortcutsInstalled) return;"
                + "window.__acpZoomShortcutsInstalled = true;"
                + "document.addEventListener('keydown', event => {"
                + "if ((!event.ctrlKey && !event.metaKey) || event.altKey) return;"
                + "let zoom = null;"
                + "if (event.key === '+' || event.key === '=' || event.code === 'NumpadAdd') zoom = 10;"
                + "if (event.key === '-' || event.code === 'NumpadSubtract') zoom = -10;"
                + "if (event.key === '0' || event.code === 'Numpad0') zoom = 0;"
                + "if (zoom === null) return;"
                + "event.preventDefault();"
                + "if (typeof window.__acpSetTranscriptZoom === 'function') window.__acpSetTranscriptZoom(zoom);"
                + "}, true);"
                + "})()");
    }

    private void changeZoom(int delta) {
        ChatSessionModel session = activeSession.get();
        if (session != null) setZoom(ProjectChatZoom.load(session.project) + delta);
    }

    private void setZoom(int requestedZoom) {
        ChatSessionModel session = activeSession.get();
        if (session == null) return;
        int zoom = requestedZoom == 0 ? ProjectChatZoom.DEFAULT : ProjectChatZoom.normalize(requestedZoom);
        if (zoom == ProjectChatZoom.load(session.project)) return;
        ProjectChatZoom.save(session.project, zoom);
        render();
    }

    private void scrollTranscriptToBottom() {
        if (transcript == null || transcript.isDisposed()) {
            return;
        }
        transcript.execute("window.scrollTo(0, Math.max(document.body.scrollHeight, document.documentElement.scrollHeight));");
    }

    void dispose() {
        transcriptSelectionBridge.dispose();
        transcriptZoomBridge.dispose();
    }

    private record TranscriptSnapshot(ChatSessionModel session, String markdown, int boundary) {
        String stableMarkdown() { return markdown.substring(0, boundary); }
        String liveMarkdown() { return markdown.substring(boundary); }
    }
}
