package io.akka.openllmetry.application;

import static org.junit.jupiter.api.Assertions.*;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.openllmetry.domain.*;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 rules 20 and 21 — a redelivered batch, and state that cannot grow without bound.
 *
 * <p>Content is appended rather than replaced, so a batch recorded twice would double the answer
 * without anything failing. That makes recording it exactly once a property of the answer, not of
 * the transport.
 */
public class ChunkDeliveryTest {

  private static final Request STREAMED =
      new Request("gpt-4o", "https://api.openai.com/v1", null, null, null, null, null, null, true);

  private static Chunk delta(String content, String finish) {
    return new Chunk(0, "assistant", content, null, List.of(), finish, null);
  }

  private static EventSourcedTestKit<CallState, CallEvent, ModelCallEntity> opened() {
    var tk = EventSourcedTestKit.of("call-d", ModelCallEntity::new);
    tk.method(ModelCallEntity::open)
        .invoke(new ModelCallEntity.Open(STREAMED,
            List.of(new Message("user", List.of(new Part.Text("hi"))))));
    return tk;
  }

  private static void send(
      EventSourcedTestKit<CallState, CallEvent, ModelCallEntity> tk, String deliveryId,
      String content, String finish) {
    tk.method(ModelCallEntity::chunk)
        .invoke(new ModelCallEntity.Chunks(
            deliveryId, "id", "gpt-4o", null, List.of(delta(content, finish)), null));
  }

  @Test
  public void aBatchRedeliveredUnderTheSameIdentifierIsNotAppendedTwice() {
    var tk = opened();
    send(tk, "batch-1", "Let ", null);
    send(tk, "batch-1", "Let ", null);
    send(tk, "batch-2", "me check.", "stop");
    tk.method(ModelCallEntity::close).invoke();

    var span = tk.method(ModelCallEntity::span).invoke().getReply().orElseThrow();
    assertTrue(((AttrValue.Str) span.attributes().get("gen_ai.output.messages")).v()
        .contains("Let me check."));
    assertEquals(2,
        tk.getAllEvents().stream().filter(e -> e instanceof CallEvent.ChunksReceived).count());
  }

  @Test
  public void aBatchWithNoIdentifierIsAlwaysRecorded() {
    var tk = opened();
    send(tk, null, "a", null);
    send(tk, null, "a", null);
    tk.method(ModelCallEntity::close).invoke();
    var span = tk.method(ModelCallEntity::span).invoke().getReply().orElseThrow();
    assertTrue(((AttrValue.Str) span.attributes().get("gen_ai.output.messages")).v().contains("aa"));
  }

  @Test
  public void theProgressWindowStopsGrowingAndSaysWhereItNowStarts() {
    var tk = opened();
    int sent = Limits.PROGRESS_ENTRIES + 25;
    for (int i = 0; i < sent; i++) {
      send(tk, "b" + i, "x", null);
    }
    var progress = tk.method(ModelCallEntity::progress).invoke().getReply();
    assertEquals(Limits.PROGRESS_ENTRIES, progress.size());
    assertEquals(sent - Limits.PROGRESS_ENTRIES, progress.get(0).sequence());
    assertEquals(sent - 1, progress.get(progress.size() - 1).sequence());
  }

  @Test
  public void theSetOfRememberedDeliveriesStopsGrowing() {
    var tk = opened();
    for (int i = 0; i < Limits.DELIVERIES_REMEMBERED + 10; i++) {
      send(tk, "b" + i, "x", null);
    }
    assertEquals(Limits.DELIVERIES_REMEMBERED, tk.getState().deliveries().size());
  }
}
