package io.akka.openllmetry.domain;

import java.util.List;

/** One of the model's answers, complete or still accumulating. */
public record Choice(
    int index,
    String role,
    String content,
    List<ToolCall> toolCalls,
    String refusal,
    String reasoning,
    String finishReason,
    boolean truncated) {

  public Choice {
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
  }

  public static Choice of(int index, String role, String content, String finishReason) {
    return new Choice(index, role, content, List.of(), null, null,
        FinishReason.rewrite(finishReason), false);
  }

  public Choice withRefusal(String value) {
    return new Choice(index, role, content, toolCalls, value, reasoning, finishReason, truncated);
  }

  public Choice withToolCalls(List<ToolCall> calls) {
    return new Choice(index, role, content, calls, refusal, reasoning, finishReason, truncated);
  }
}
