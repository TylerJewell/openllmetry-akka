package io.akka.openllmetry.domain;

/** SPEC-001 rule 8 — the two tool-shaped reasons collapse to one; everything else is verbatim. */
public final class FinishReason {

  public static String rewrite(String reason) {
    if (reason == null) return null;
    return switch (reason) {
      case "tool_calls", "function_call" -> "tool_call";
      default -> reason;
    };
  }

  private FinishReason() {}
}
