package io.akka.openllmetry.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 rules 12 and 13, and decision D3 — question-log rows 9, 10, 24. */
public class ChunkAccumulationTest {

  private static Chunk delta(int index, String role, String content, String finishReason) {
    return new Chunk(index, role, content, null, List.of(), finishReason, null);
  }

  @Test
  public void contentDeltasConcatenateInArrivalOrder() {
    var choices =
        Accumulator.apply(
            List.of(),
            List.of(
                delta(0, "assistant", "", null),
                delta(0, null, "Let ", null),
                delta(0, null, "me ", null),
                delta(0, null, "check.", null)));
    assertEquals(1, choices.size());
    assertEquals("Let me check.", choices.get(0).content());
    assertEquals("assistant", choices.get(0).role());
  }

  @Test
  public void toolCallArgumentFragmentsConcatenatePerToolCallIndex() {
    var choices =
        Accumulator.apply(
            List.of(),
            List.of(
                new Chunk(
                    0,
                    "assistant",
                    null,
                    null,
                    List.of(new Chunk.ToolCallDelta(0, "call_a", "get_weather", "{\"ci")),
                    null,
                    null),
                new Chunk(
                    0, null, null, null,
                    List.of(new Chunk.ToolCallDelta(0, null, null, "ty\":\"Oslo\"}")),
                    null, null)));
    var calls = choices.get(0).toolCalls();
    assertEquals(1, calls.size());
    assertEquals("call_a", calls.get(0).id());
    assertEquals("get_weather", calls.get(0).name());
    assertEquals("{\"city\":\"Oslo\"}", calls.get(0).arguments());
  }

  @Test
  public void twoToolCallsAtDifferentIndicesDoNotShareAnArgumentBuffer() {
    var choices =
        Accumulator.apply(
            List.of(),
            List.of(
                new Chunk(0, "assistant", null, null,
                    List.of(new Chunk.ToolCallDelta(0, "a", "f", "{\"x\":"),
                            new Chunk.ToolCallDelta(1, "b", "g", "{\"y\":")),
                    null, null),
                new Chunk(0, null, null, null,
                    List.of(new Chunk.ToolCallDelta(0, null, null, "1}"),
                            new Chunk.ToolCallDelta(1, null, null, "2}")),
                    null, null)));
    var calls = choices.get(0).toolCalls();
    assertEquals("{\"x\":1}", calls.get(0).arguments());
    assertEquals("{\"y\":2}", calls.get(1).arguments());
  }

  @Test
  public void aFinishReasonArrivingOnItsOwnChunkIsRewrittenAndKept() {
    var choices =
        Accumulator.apply(
            List.of(),
            List.of(delta(0, "assistant", "x", null), delta(0, null, null, "tool_calls")));
    assertEquals("tool_call", choices.get(0).finishReason());
    assertEquals("x", choices.get(0).content());
  }

  @Test
  public void severalChoiceIndicesAccumulateSideBySide() {
    var choices =
        Accumulator.apply(
            List.of(),
            List.of(
                delta(1, "assistant", "second", "stop"),
                delta(0, "assistant", "first", "stop")));
    assertEquals(2, choices.size());
    assertEquals("first", choices.get(0).content());
    assertEquals("second", choices.get(1).content());
  }

  @Test
  public void accumulationResumesFromChoicesAlreadyRecorded() {
    var half = Accumulator.apply(List.of(), List.of(delta(0, "assistant", "Let ", null)));
    var whole = Accumulator.apply(half, List.of(delta(0, null, "me check.", "stop")));
    assertEquals("Let me check.", whole.get(0).content());
    assertEquals("stop", whole.get(0).finishReason());
  }

  @Test
  public void aStreamedChoiceThatCarriedNoContentStillHasAnEmptyAnswerRatherThanNone() {
    var choices =
        Accumulator.apply(
            List.of(),
            List.of(
                new Chunk(0, "assistant", null, null,
                    List.of(new Chunk.ToolCallDelta(0, "a", "f", "{}")), null, null),
                delta(0, null, null, "tool_calls")));
    assertEquals("", choices.get(0).content());
  }

  @Test
  public void aChunkCarryingNothingAtAllDoesNotConjureAChoice() {
    var empty = new Chunk(0, null, null, null, List.of(), null, null);
    assertEquals(List.of(), Accumulator.apply(List.of(), List.of(empty)));

    var existing = Accumulator.apply(List.of(), List.of(delta(0, "assistant", "x", null)));
    var after = Accumulator.apply(existing, List.of(empty));
    assertEquals(1, after.size());
    assertEquals("x", after.get(0).content());
  }

  @Test
  public void aChoiceIndexFirstSeenAboveTheCountIsAccumulatedLikeAnyOther() {
    // SPEC-001 rule 22. The choices are held by index rather than by position, so a delta
    // naming choice 2 before any delta named choice 0 opens choice 2 and leaves a gap.
    var choices =
        Accumulator.apply(
            List.of(),
            List.of(
                delta(2, "assistant", "C", null),
                delta(0, "assistant", "A", null),
                delta(1, "assistant", "B", null)));
    assertEquals(3, choices.size());
    assertEquals(List.of(0, 1, 2), choices.stream().map(Choice::index).toList());
    assertEquals(List.of("A", "B", "C"), choices.stream().map(Choice::content).toList());
  }

  @Test
  public void aChoiceIndexArrivingAloneLeavesNoPhantomChoicesBelowIt() {
    var choices = Accumulator.apply(List.of(), List.of(delta(3, "assistant", "D", "stop")));
    assertEquals(1, choices.size());
    assertEquals(3, choices.get(0).index());
  }

  @Test
  public void recordedContentIsCappedAndTheCapIsVisible() {
    var over = "x".repeat(Accumulator.CONTENT_CAP + 500);
    var choices = Accumulator.apply(List.of(), List.of(delta(0, "assistant", over, "length")));
    assertEquals(Accumulator.CONTENT_CAP, choices.get(0).content().length());
    assertTrue(choices.get(0).truncated());
  }

  @Test
  public void contentUnderTheCapIsNotMarkedTruncated() {
    var choices = Accumulator.apply(List.of(), List.of(delta(0, "assistant", "short", "stop")));
    assertFalse(choices.get(0).truncated());
  }

  @Test
  public void aCapReachedByManyDeltasIsStillACap() {
    var piece = "y".repeat(1024);
    var deltas = new java.util.ArrayList<Chunk>();
    for (int i = 0; i < (Accumulator.CONTENT_CAP / 1024) + 2; i++) {
      deltas.add(delta(0, "assistant", piece, null));
    }
    var choices = Accumulator.apply(List.of(), deltas);
    assertEquals(Accumulator.CONTENT_CAP, choices.get(0).content().length());
    assertTrue(choices.get(0).truncated());
  }
}
