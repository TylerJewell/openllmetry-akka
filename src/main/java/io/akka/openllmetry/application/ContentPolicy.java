package io.akka.openllmetry.application;

/**
 * SPEC-001 rule 16 — whether prompts and completions are put on the span.
 *
 * <p>Off means the two message attributes are absent and nothing else changes, so the switch can be
 * read at derivation time rather than at recording time: what was recorded is unaffected by it.
 */
public final class ContentPolicy {

  private static final String VARIABLE = "TRACELOOP_TRACE_CONTENT";

  public static boolean traceContent() {
    var value = System.getenv(VARIABLE);
    return value == null || truthy(value);
  }

  static boolean truthy(String value) {
    return switch (value.trim().toLowerCase()) {
      case "true", "1", "yes", "on" -> true;
      default -> false;
    };
  }

  private ContentPolicy() {}
}
