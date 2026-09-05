package dev.eclipseacp.client.acp;

/** A complete file replacement supplied by ACP's {@code ToolCallContent::Diff}. */
public record FileDiff(String path, String oldText, String newText) {
    public FileDiff {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("A diff path is required");
        if (newText == null) throw new IllegalArgumentException("A diff newText is required");
    }

    public boolean createsFile() { return oldText == null; }
}
