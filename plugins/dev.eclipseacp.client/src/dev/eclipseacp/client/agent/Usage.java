package dev.eclipseacp.client.agent;

/** Usage information supplied by an agent, with absent metrics represented by {@code null}. */
public record Usage(Long inputTokens, Long outputTokens, Long totalTokens, String cost) {
}
