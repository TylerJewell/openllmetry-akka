package io.akka.openllmetry.application;

import static org.junit.jupiter.api.Assertions.*;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.openllmetry.domain.*;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 rules 11, 14, 15, 18 and 19 — question-log rows 8, 11, 15, 21 to 23. */
public class ModelCallEntityTest {

  private static final Request STREAMED =
      new Request("gpt-4o", "https://api.openai.com/v1", null, null, null, null, null, null, true);

  private static Chunk delta(int i, String role, String content, String finish) {
    return new Chunk(i, role, content, null, List.of(), finish, null);
  }

  private static EventSourcedTestKit<CallState, CallEvent, ModelCallEntity> opened() {
    var tk = EventSourcedTestKit.of("call-1", ModelCallEntity::new);
    tk.method(ModelCallEntity::open)
        .invoke(new ModelCallEntity.Open(STREAMED,
            List.of(new Message("user", List.of(new Part.Text("weather in Oslo?"))))));
    return tk;
  }

  @Test
  public void aStreamedCallHasNoSpanUntilTheChunkSequenceIsClosed() {
    var tk = opened();
    tk.method(ModelCallEntity::chunk)
        .invoke(new ModelCallEntity.Chunks(null, "chatcmpl-2", "gpt-4o-2024-08-06", null,
            List.of(delta(0, "assistant", "Let ", null)), null));
    assertTrue(tk.method(ModelCallEntity::span).invoke().getReply().isEmpty());

    tk.method(ModelCallEntity::chunk)
        .invoke(new ModelCallEntity.Chunks(null, "chatcmpl-2", "gpt-4o-2024-08-06", null,
            List.of(delta(0, null, "me check.", "stop")), new Usage(20L, 9L, 29L, null)));
    assertTrue(tk.method(ModelCallEntity::span).invoke().getReply().isEmpty());

    tk.method(ModelCallEntity::close).invoke();
    var span = tk.method(ModelCallEntity::span).invoke().getReply().orElseThrow();
    assertEquals(Span.Status.OK, span.status());
    assertEquals(new AttrValue.Num(29L), span.attributes().get("gen_ai.usage.total_tokens"));
    assertTrue(((AttrValue.Str) span.attributes().get("gen_ai.output.messages")).v()
        .contains("Let me check."));
  }

  @Test
  public void aPartialStreamRebuiltFromTheJournalGivesTheSameSpanAsAnUninterruptedOne() {
    var chunks = List.of(
        delta(0, "assistant", "Let ", null),
        delta(0, null, "me ", null),
        delta(0, null, "check.", "stop"));

    var interrupted = opened();
    for (var c : chunks) {
      interrupted.method(ModelCallEntity::chunk)
          .invoke(new ModelCallEntity.Chunks(null, "id", "gpt-4o", null, List.of(c), null));
    }
    interrupted.method(ModelCallEntity::close).invoke();

    var straight = opened();
    straight.method(ModelCallEntity::chunk)
        .invoke(new ModelCallEntity.Chunks(null, "id", "gpt-4o", null, chunks, null));
    straight.method(ModelCallEntity::close).invoke();

    assertEquals(
        straight.method(ModelCallEntity::span).invoke().getReply(),
        interrupted.method(ModelCallEntity::span).invoke().getReply());
  }

  @Test
  public void replayingTheJournalRebuildsTheSameStateTheLiveEntityHeld() {
    var tk = opened();
    tk.method(ModelCallEntity::chunk)
        .invoke(new ModelCallEntity.Chunks(null, "id", "gpt-4o", null,
            List.of(delta(0, "assistant", "Let ", null)), null));
    var live = tk.getState();

    var replayed = CallState.empty();
    for (var e : tk.getAllEvents()) {
      replayed = replayed.apply((CallEvent) e);
    }
    assertEquals(live, replayed);
  }

  @Test
  public void aFailedCallEndsInErrorAndKeepsWhatWasAlreadyRecorded() {
    var tk = opened();
    tk.method(ModelCallEntity::chunk)
        .invoke(new ModelCallEntity.Chunks(null, "id", "gpt-4o", null,
            List.of(delta(0, "assistant", "Let ", null)), null));
    tk.method(ModelCallEntity::fail)
        .invoke(new ModelCallEntity.Failed("APIConnectionError", "connection reset"));

    var span = tk.method(ModelCallEntity::span).invoke().getReply().orElseThrow();
    assertEquals(Span.Status.ERROR, span.status());
    assertEquals(new AttrValue.Str("APIConnectionError"), span.attributes().get("error.type"));
    assertTrue(span.attributes().containsKey("gen_ai.input.messages"));
  }

  @Test
  public void aChunkArrivingAfterTheSequenceIsClosedIsRejected() {
    var tk = opened();
    tk.method(ModelCallEntity::close).invoke();
    var result = tk.method(ModelCallEntity::chunk)
        .invoke(new ModelCallEntity.Chunks(null, "id", "gpt-4o", null,
            List.of(delta(0, "assistant", "late", null)), null));
    assertTrue(result.isError());
    assertEquals(
        0,
        tk.getAllEvents().stream().filter(e -> e instanceof CallEvent.ChunksReceived).count());
  }

  @Test
  public void aSuppressedCallProducesNoSpanHowFarAlongItGot() {
    var tk = EventSourcedTestKit.of("call-1", ModelCallEntity::new);
    tk.method(ModelCallEntity::open)
        .invoke(new ModelCallEntity.Open(STREAMED, List.of()));
    tk.method(ModelCallEntity::suppress).invoke();
    tk.method(ModelCallEntity::close).invoke();
    assertTrue(tk.method(ModelCallEntity::span).invoke().getReply().isEmpty());
  }

  @Test
  public void everyRecordedChunkIsReadableAsAProgressLogForAReaderThatReconnects() {
    // D4. The source has no answer here; the port serves a reconnecting reader from the
    // record rather than from what happened to be in flight.
    var tk = opened();
    for (var s : List.of("Let ", "me ", "check.")) {
      tk.method(ModelCallEntity::chunk)
          .invoke(new ModelCallEntity.Chunks(null, "id", "gpt-4o", null,
              List.of(delta(0, "assistant", s, null)), null));
    }
    var progress = tk.method(ModelCallEntity::progress).invoke().getReply();
    assertEquals(List.of("Let ", "me ", "check."),
        progress.stream().map(ModelCallEntity.Progress::text).toList());
    assertEquals(List.of(0L, 1L, 2L),
        progress.stream().map(ModelCallEntity.Progress::sequence).toList());
  }
}
