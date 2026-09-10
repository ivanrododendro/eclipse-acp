package dev.eclipseacp.client.agent;

/** A text response requested by an agent during a session. */
public record ElicitationRequest(String title, String message) {
    public ElicitationRequest {
        title = title == null ? "" : title;
        message = message == null ? "" : message;
    }
}
