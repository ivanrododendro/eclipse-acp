package dev.eclipseacp.client.agent;

/** A complete file replacement proposed by an agent tool. */
public record FileDiff(String path, String oldText, String newText) {
    public FileDiff {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("A diff path is required");
        if (newText == null) throw new IllegalArgumentException("A diff newText is required");
    }
    public boolean createsFile() { return oldText == null; }
}
