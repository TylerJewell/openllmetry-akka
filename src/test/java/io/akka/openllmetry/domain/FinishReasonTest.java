package io.akka.openllmetry.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** SPEC-001 rule 8 — question-log row 7. */
public class FinishReasonTest {

  @Test
  public void bothToolShapedReasonsBecomeTheSingularForm() {
    assertEquals("tool_call", FinishReason.rewrite("tool_calls"));
    assertEquals("tool_call", FinishReason.rewrite("function_call"));
  }

  @Test
  public void everyOtherReasonPassesThroughUnchanged() {
    assertEquals("stop", FinishReason.rewrite("stop"));
    assertEquals("length", FinishReason.rewrite("length"));
    assertEquals("content_filter", FinishReason.rewrite("content_filter"));
    assertEquals("something_new", FinishReason.rewrite("something_new"));
  }

  @Test
  public void anAbsentReasonStaysAbsent() {
    assertNull(FinishReason.rewrite(null));
  }
}
