package io.akka.openllmetry.domain;

import java.util.List;

/**
 * One streamed delta for one choice, as the model API sends it.
 *
 * <p>Every field but {@code index} may be absent: a chunk carries whichever of role, content,
 * reasoning, tool-call fragments, refusal and finish reason the API had to send at that moment.
 */
public record Chunk(
    int index,
    String role,
    String content,
    String reasoning,
    List<ToolCallDelta> toolCalls,
    String finishReason,
    String refusal) {

  public Chunk {
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
  }

  /**
   * Whether this chunk carries nothing about an answer. The usage-only chunk a stream ends with
   * is the reachable case: it is an item in the stream, so it is counted, but it names no choice.
   */
  public boolean carriesNothing() {
    return role == null
        && content == null
        && reasoning == null
        && refusal == null
        && finishReason == null
        && toolCalls.isEmpty();
  }

  /** A fragment of one tool call. Arguments arrive split across chunks and are concatenated. */
  public record ToolCallDelta(int index, String id, String name, String arguments) {}
}
