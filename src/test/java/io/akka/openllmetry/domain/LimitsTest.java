package io.akka.openllmetry.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 decision D3 and rule 21 — what one call may hold. */
public class LimitsTest {

  @Test
  public void anInlineImageIsCappedWhereItIsRecorded() {
    var oversize = "A".repeat(Limits.CONTENT_CAP + 4096);
    var capped =
        Limits.capped(
            List.of(new Message("user", List.of(new Part.Blob("image", "image/png", oversize)))));
    var blob = (Part.Blob) capped.get(0).parts().get(0);
    assertEquals(Limits.CONTENT_CAP, blob.content().length());
    assertEquals("image/png", blob.mimeType());
  }

  @Test
  public void everyPartThatCarriesFreeTextIsCappedAndTheRestIsLeftAlone() {
    var oversize = "A".repeat(Limits.CONTENT_CAP + 1);
    var capped =
        Limits.capped(
            List.of(
                new Message("user", List.of(
                    new Part.Text(oversize),
                    new Part.Uri("image", "https://example.com/a.png"),
                    new Part.ToolCall("t", "f", oversize),
                    new Part.ToolResponse("t", oversize),
                    new Part.Refusal(oversize),
                    new Part.Reasoning(oversize)))));
    var parts = capped.get(0).parts();
    assertEquals(Limits.CONTENT_CAP, ((Part.Text) parts.get(0)).content().length());
    assertEquals("https://example.com/a.png", ((Part.Uri) parts.get(1)).uri());
    assertEquals(Limits.CONTENT_CAP, ((Part.ToolCall) parts.get(2)).arguments().length());
    assertEquals(Limits.CONTENT_CAP, ((Part.ToolResponse) parts.get(3)).response().length());
    assertEquals(Limits.CONTENT_CAP, ((Part.Refusal) parts.get(4)).content().length());
    assertEquals(Limits.CONTENT_CAP, ((Part.Reasoning) parts.get(5)).content().length());
  }

  @Test
  public void contentUnderTheCapIsUntouchedAndIdenticalToWhatWasGiven() {
    var messages = List.of(new Message("user", List.of(new Part.Text("hi"))));
    assertEquals(messages, Limits.capped(messages));
  }

  @Test
  public void aGrowingListKeepsItsMostRecentEntries() {
    var values = java.util.stream.IntStream.range(0, 10).boxed().toList();
    assertEquals(List.of(7, 8, 9), Limits.lastOf(values, 3));
    assertEquals(values, Limits.lastOf(values, 100));
  }
}
