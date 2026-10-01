package dev.eclipseacp.client.agent;

/** Usage information supplied by an agent, with absent metrics represented by {@code null}. */
public record Usage(Long inputTokens, Long outputTokens, Long totalTokens, String cost, Long used, Long size) {
    /** Compatibility constructor for agents that only report per-request token metrics. */
    public Usage(Long inputTokens, Long outputTokens, Long totalTokens, String cost) {
        this(inputTokens, outputTokens, totalTokens, cost, null, null);
    }
}
