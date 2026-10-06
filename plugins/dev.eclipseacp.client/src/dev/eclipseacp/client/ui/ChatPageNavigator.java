package dev.eclipseacp.client.ui;

import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;

/** Owns page geometry and transitions; header presentation remains with the view. */
final class ChatPageNavigator {
    private final Composite pageHost;
    private final Composite sessionsPage;
    private final Composite chatPage;
    private final Consumer<Boolean> changed;
    private final Consumer<Boolean> finished;
    private boolean chatPageVisible;
    private int pageAnimationGeneration;

    ChatPageNavigator(Composite pageHost, Composite sessionsPage, Composite chatPage,
            Consumer<Boolean> changed, Consumer<Boolean> finished) {
        this.pageHost = pageHost;
        this.sessionsPage = sessionsPage;
        this.chatPage = chatPage;
        this.changed = changed;
        this.finished = finished;
        chatPage.setVisible(false);
        pageHost.addListener(SWT.Resize, ignored -> layoutPages());
    }

    boolean isChatVisible() { return chatPageVisible; }

    void show(boolean showChat, boolean animate) {
        if (pageHost == null || pageHost.isDisposed() || sessionsPage == null || chatPage == null) return;
        if (chatPageVisible == showChat && (showChat ? chatPage.getVisible() : sessionsPage.getVisible())) return;

        chatPageVisible = showChat;
        changed.accept(showChat);
        int generation = ++pageAnimationGeneration;
        var area = pageHost.getClientArea();
        int width = area.width;
        int height = area.height;
        if (!animate || width <= 0 || height <= 0) {
            finishPageTransition(showChat);
            return;
        }

        if (!sessionsPage.getVisible()) sessionsPage.setBounds(-width, 0, width, height);
        if (!chatPage.getVisible()) chatPage.setBounds(width, 0, width, height);
        sessionsPage.setVisible(true);
        chatPage.setVisible(true);
        int sessionsStart = sessionsPage.getBounds().x;
        int chatStart = chatPage.getBounds().x;
        int sessionsEnd = showChat ? -width : 0;
        int chatEnd = showChat ? 0 : width;
        long started = System.nanoTime();
        int durationMillis = 200;

        Runnable animation = new Runnable() {
            @Override public void run() {
                if (generation != pageAnimationGeneration || pageHost.isDisposed()) return;
                double elapsedMillis = (System.nanoTime() - started) / 1_000_000d;
                double progress = Math.min(1d, elapsedMillis / durationMillis);
                double eased = 1d - Math.pow(1d - progress, 3d);
                int sessionsX = sessionsStart + (int) Math.round((sessionsEnd - sessionsStart) * eased);
                int chatX = chatStart + (int) Math.round((chatEnd - chatStart) * eased);
                sessionsPage.setBounds(sessionsX, 0, width, height);
                chatPage.setBounds(chatX, 0, width, height);
                if (progress < 1d) {
                    pageHost.getDisplay().timerExec(16, this);
                } else {
                    finishPageTransition(showChat);
                }
            }
        };
        pageHost.getDisplay().timerExec(0, animation);
    }

    private void finishPageTransition(boolean showChat) {
        if (pageHost == null || pageHost.isDisposed()) return;
        var area = pageHost.getClientArea();
        sessionsPage.setBounds(showChat ? -area.width : 0, 0, area.width, area.height);
        chatPage.setBounds(showChat ? 0 : area.width, 0, area.width, area.height);
        sessionsPage.setVisible(!showChat);
        chatPage.setVisible(showChat);
        finished.accept(showChat);
    }

    private void layoutPages() {
        if (pageHost == null || pageHost.isDisposed() || sessionsPage == null || chatPage == null) return;
        // A resize invalidates frames computed using the previous page dimensions.
        ++pageAnimationGeneration;
        finishPageTransition(chatPageVisible);
    }
}
