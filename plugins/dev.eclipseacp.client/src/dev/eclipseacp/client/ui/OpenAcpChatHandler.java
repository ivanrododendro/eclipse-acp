package dev.eclipseacp.client.ui;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;

/** Opens a session for the owning project, even when invoked on a child resource. */
public final class OpenAcpChatHandler extends AbstractHandler {
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        var selection = HandlerUtil.getCurrentSelectionChecked(event);
        Object element = selection instanceof IStructuredSelection structured && !structured.isEmpty()
                ? structured.getFirstElement() : null;
        IResource resource = element instanceof IResource direct ? direct
                : element instanceof IAdaptable adaptable ? adaptable.getAdapter(IResource.class) : null;
        IProject project = resource == null ? null : resource.getProject();
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ExecutionException("Select an open Eclipse project or one of its resources");
        }
        try {
            IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
            AcpChatView view = (AcpChatView) page.showView(AcpChatView.ID);
            view.openSessionFor(project);
            return null;
        } catch (Exception exception) {
            throw new ExecutionException("Could not open ACP chat", exception);
        }
    }
}
