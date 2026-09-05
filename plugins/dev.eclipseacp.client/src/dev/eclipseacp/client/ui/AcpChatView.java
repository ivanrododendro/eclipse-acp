package dev.eclipseacp.client.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

import dev.eclipseacp.client.acp.AcpListener;
import dev.eclipseacp.client.acp.PermissionOption;
import dev.eclipseacp.client.agent.AgentClient;
import dev.eclipseacp.client.agent.AgentClientFactory;
import dev.eclipseacp.client.agent.AgentProvider;
import dev.eclipseacp.client.preferences.AgentProviderRegistry;
import dev.eclipseacp.client.preferences.AcpPreferences;

public final class AcpChatView extends ViewPart implements AcpListener {
    public static final String ID = "dev.eclipseacp.client.views.chat";
    private Browser transcript;
    private final StringBuilder transcriptMarkdown = new StringBuilder();
    private Text prompt;
    private Button sendButton;
    private Button stopButton;
    private Label status;
    private Combo projectSelector;
    private AgentClient client;
    private IProject selectedProject;
    private boolean agentMessageOpen;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        Composite header = new Composite(parent, SWT.NONE);
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        header.setLayout(new GridLayout(2, false));
        Label projectLabel = new Label(header, SWT.NONE);
        projectLabel.setText("Project:");
        projectSelector = new Combo(header, SWT.DROP_DOWN | SWT.READ_ONLY);
        projectSelector.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
        loadProjects();
        projectSelector.addListener(SWT.Selection, ignored -> selectProjectFromCombo());

        transcript = new Browser(parent, SWT.BORDER);
        transcript.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        transcript.addProgressListener(new ProgressAdapter() {
            @Override
            public void completed(org.eclipse.swt.browser.ProgressEvent event) {
                scrollTranscriptToBottom();
            }
        });
        transcript.setText(GfmRenderer.document(""));

        prompt = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        GridData promptData = new GridData(SWT.FILL, SWT.FILL, true, false);
        promptData.heightHint = 72;
        prompt.setLayoutData(promptData);
        prompt.setMessage("Ask the ACP agent…  (Ctrl+Enter to send)");
        prompt.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if ((event.stateMask & SWT.MOD1) != 0 && (event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR)) {
                    sendPrompt();
                }
            }
        });

        Composite actions = new Composite(parent, SWT.NONE);
        actions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        actions.setLayout(new GridLayout(3, false));

        sendButton = new Button(actions, SWT.PUSH);
        sendButton.setText("Send");
        sendButton.setEnabled(false);
        sendButton.addListener(SWT.Selection, ignored -> sendPrompt());

        stopButton = new Button(actions, SWT.PUSH);
        stopButton.setText("Stop");
        stopButton.setEnabled(false);
        stopButton.addListener(SWT.Selection, ignored -> cancel());

        status = new Label(actions, SWT.NONE);
        status.setText("Not connected");
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    /** Called only by the project/resource context-menu command. */
    public void openSessionFor(IProject project) {
        if (project == null || !project.exists() || !project.isOpen() || project.getLocation() == null) {
            onError("Cannot open ACP session", new IllegalArgumentException("The selected project is not open"));
            return;
        }
        disconnect();
        selectedProject = project;
        selectProject(project);
        AgentProvider provider = new AgentProviderRegistry(AcpPreferences.store()).active();
        String agentName = provider.name();

        append("Connecting to " + agentName + " in " + project.getLocation() + "…\n\n");
        projectSelector.setEnabled(false);
        AgentClient newClient = AgentClientFactory.create(provider, this);
        client = newClient;
        newClient.connect(workingDirectory()).whenComplete((ignored, error) -> ui(() -> {
            if (client != newClient) {
                return; // A newer connection replaced this one.
            }
            if (error != null) {
                onError("Could not start " + agentName, unwrap(error));
                disconnect();
                return;
            }
            sendButton.setEnabled(true);
            prompt.setFocus();
        }));
    }

    private void loadProjects() {
        if (projectSelector == null || projectSelector.isDisposed()) return;
        projectSelector.removeAll();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (project.isOpen()) projectSelector.add(project.getName());
        }
    }

    private void selectProjectFromCombo() {
        int index = projectSelector.getSelectionIndex();
        if (index < 0) return;
        int openIndex = 0;
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (project.isOpen()) {
                if (openIndex++ == index) { selectedProject = project; status.setText("Ready to open a session for " + project.getName()); break; }
            }
        }
    }

    private void selectProject(IProject project) {
        for (int i = 0; i < projectSelector.getItemCount(); i++) if (projectSelector.getItem(i).equals(project.getName())) { projectSelector.select(i); break; }
    }

    private void sendPrompt() {
        String text = prompt.getText().trim();
        if (text.isEmpty() || client == null) {
            return;
        }
        prompt.setText("");
        agentMessageOpen = false;
        append("## You\n\n" + text + "\n\n## Agent\n\n");
        agentMessageOpen = true;
        sendButton.setEnabled(false);
        stopButton.setEnabled(true);

        AgentClient activeClient = client;
        activeClient.prompt(text).whenComplete((ignored, error) -> ui(() -> {
            if (client != activeClient) {
                return; // The response belongs to an earlier connection.
            }
            if (error != null) {
                onError("Prompt failed", unwrap(error));
            } else if (agentMessageOpen) {
                append("\n\n");
            }
            agentMessageOpen = false;
            sendButton.setEnabled(true);
            stopButton.setEnabled(false);
        }));
    }

    private void cancel() {
        if (client == null) {
            return;
        }
        try {
            client.cancel();
        } catch (IOException exception) {
            onError("Could not cancel the current prompt", exception);
        }
    }

    private Path workingDirectory() {
        if (selectedProject != null && selectedProject.getLocation() != null) {
            return selectedProject.getLocation().toFile().toPath();
        }
        return ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath();
    }

    @Override
    public void onAgentText(String text) {
        ui(() -> append(text));
    }

    @Override
    public void onStatus(String value) {
        ui(() -> {
            if (status != null && !status.isDisposed()) {
                status.setText(value);
                status.getParent().layout();
            }
        });
    }

    @Override
    public void onError(String message, Throwable error) {
        ui(() -> {
            String detail = error == null || error.getMessage() == null ? "" : ": " + error.getMessage();
            append("\n> **Error:** " + message + detail + "\n\n");
            onStatus("Error");
        });
    }

    @Override
    public CompletableFuture<String> requestPermission(String title, List<PermissionOption> options) {
        CompletableFuture<String> result = new CompletableFuture<>();
        ui(() -> {
            if (options.isEmpty() || getSite().getShell().isDisposed()) {
                result.complete(null);
                return;
            }
            String[] labels = options.stream().map(PermissionOption::name).toArray(String[]::new);
            int defaultIndex = defaultPermissionIndex(options);
            MessageDialog dialog = new MessageDialog(
                    getSite().getShell(),
                    "ACP permission",
                    null,
                    title,
                    MessageDialog.QUESTION,
                    labels,
                    defaultIndex);
            int selected = dialog.open();
            result.complete(selected >= 0 && selected < options.size() ? options.get(selected).id() : null);
        });
        return result;
    }

    private static int defaultPermissionIndex(List<PermissionOption> options) {
        for (int index = 0; index < options.size(); index++) {
            if (options.get(index).kind().startsWith("reject")) {
                return index;
            }
        }
        return options.size() - 1;
    }

    private void append(String text) {
        if (transcript == null || transcript.isDisposed()) {
            return;
        }
        transcriptMarkdown.append(text);
        transcript.setText(GfmRenderer.document(transcriptMarkdown.toString()));
        // setText starts an asynchronous page load; the progress listener above
        // repeats this after the new document has been laid out.
        scrollTranscriptToBottom();
    }

    private void scrollTranscriptToBottom() {
        if (transcript == null || transcript.isDisposed()) {
            return;
        }
        transcript.execute("window.scrollTo(0, Math.max(document.body.scrollHeight, document.documentElement.scrollHeight));");
    }

    private void disconnect() {
        AgentClient previousClient = client;
        client = null;
        if (previousClient != null) {
            // Closing a subprocess pipe can wait for a blocked reader. Never do it on SWT's UI thread.
            CompletableFuture.runAsync(previousClient::close);
        }
        if (sendButton != null && !sendButton.isDisposed()) {
            sendButton.setEnabled(false);
        }
        if (stopButton != null && !stopButton.isDisposed()) {
            stopButton.setEnabled(false);
        }
        if (projectSelector != null && !projectSelector.isDisposed()) projectSelector.setEnabled(true);
    }

    private void ui(Runnable action) {
        Display display = getSite().getShell().getDisplay();
        if (display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            if (transcript != null && !transcript.isDisposed()) {
                action.run();
            }
        });
    }

    private static Throwable unwrap(Throwable error) {
        return error.getCause() == null ? error : error.getCause();
    }

    @Override
    public void setFocus() {
        prompt.setFocus();
    }

    @Override
    public void dispose() {
        disconnect();
        super.dispose();
    }
}
