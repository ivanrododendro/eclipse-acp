package dev.eclipseacp.client.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.part.ViewPart;

import dev.eclipseacp.client.acp.AcpClient;
import dev.eclipseacp.client.acp.AcpListener;
import dev.eclipseacp.client.acp.PermissionOption;
import dev.eclipseacp.client.preferences.AcpPreferences;

public final class AcpChatView extends ViewPart implements AcpListener {
    private StyledText transcript;
    private Text prompt;
    private Button connectButton;
    private Button sendButton;
    private Button stopButton;
    private Label status;
    private AcpClient client;
    private boolean agentMessageOpen;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        transcript = new StyledText(parent, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        transcript.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        transcript.setAlwaysShowScrollBars(false);

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
        actions.setLayout(new GridLayout(4, false));

        connectButton = new Button(actions, SWT.PUSH);
        connectButton.setText("Connect");
        connectButton.addListener(SWT.Selection, ignored -> connect());

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

    private void connect() {
        disconnect();
        String command = AcpPreferences.store().getString(AcpPreferences.AGENT_COMMAND);
        String arguments = AcpPreferences.store().getString(AcpPreferences.AGENT_ARGUMENTS);
        String agentName = AcpPreferences.store().getString(AcpPreferences.AGENT_NAME);

        append("Connecting to " + agentName + " in " + workingDirectory() + "…\n\n");
        connectButton.setEnabled(false);
        client = new AcpClient(command, arguments, this);
        client.connect(workingDirectory()).whenComplete((ignored, error) -> ui(() -> {
            if (error != null) {
                onError("Could not start " + agentName, unwrap(error));
                disconnect();
                return;
            }
            sendButton.setEnabled(true);
            connectButton.setText("Reconnect");
            connectButton.setEnabled(true);
            prompt.setFocus();
        }));
    }

    private void sendPrompt() {
        String text = prompt.getText().trim();
        if (text.isEmpty() || client == null) {
            return;
        }
        prompt.setText("");
        agentMessageOpen = false;
        append("You:\n" + text + "\n\nAgent:\n");
        agentMessageOpen = true;
        sendButton.setEnabled(false);
        stopButton.setEnabled(true);

        client.prompt(text).whenComplete((ignored, error) -> ui(() -> {
            if (error != null) {
                onError("Prompt failed", unwrap(error));
            } else if (agentMessageOpen) {
                append("\n\n");
            }
            agentMessageOpen = false;
            sendButton.setEnabled(client != null);
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
        IWorkbenchPart activePart = getSite().getPage().getActivePart();
        if (activePart != null && activePart.getSite().getSelectionProvider() != null) {
            var selection = activePart.getSite().getSelectionProvider().getSelection();
            if (selection instanceof IStructuredSelection structured && structured.getFirstElement() instanceof IResource resource
                    && resource.getProject().getLocation() != null) {
                return resource.getProject().getLocation().toFile().toPath();
            }
        }

        var editor = getSite().getPage().getActiveEditor();
        if (editor != null && editor.getEditorInput() instanceof IFileEditorInput fileInput) {
            IFile file = fileInput.getFile();
            if (file.getProject().getLocation() != null) {
                return file.getProject().getLocation().toFile().toPath();
            }
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
            append("\n[Error] " + message + detail + "\n\n");
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
        transcript.append(text);
        transcript.setTopIndex(transcript.getLineCount() - 1);
    }

    private void disconnect() {
        if (client != null) {
            client.close();
            client = null;
        }
        if (sendButton != null && !sendButton.isDisposed()) {
            sendButton.setEnabled(false);
            stopButton.setEnabled(false);
            connectButton.setEnabled(true);
        }
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
