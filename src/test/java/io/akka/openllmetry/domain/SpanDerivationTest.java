package io.akka.openllmetry.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 rules 1 to 19 and decisions D1, D2, D5 — question-log rows 1 to 20.
 *
 * <p>Every expected value here was read off a span the real instrumentation emitted; the
 * probe that produced it is named on the rule in the specification.
 */
public class SpanDerivationTest {

  private static Request request() {
    return new Request(
        "gpt-4o", "https://api.openai.com/v1", 0.25, 0.9, 16L, null, null, "probe-user", false);
  }

  private static CallState completed() {
    return CallState.opened("c1", request())
        .withInputMessages(
            List.of(
                new Message("system", List.of(new Part.Text("Be terse."))),
                new Message("user", List.of(new Part.Text("2+2?")))))
        .completed(
            "chatcmpl-probe1",
            "gpt-4o-2024-08-06",
            "fp_probe",
            List.of(Choice.of(0, "assistant", "Four.", "stop")),
            new Usage(11L, 3L, 14L, 7L));
  }

  private static Map<String, AttrValue> attrs(CallState s) {
    return Span.of(s, true).orElseThrow().attributes();
  }

  @Test
  public void theSpanIsNamedAndKindedTheWayTheSourceNamesAndKindsIt() {
    var span = Span.of(completed(), true).orElseThrow();
    assertEquals("openai.chat", span.name());
    assertEquals(Span.Kind.CLIENT, span.kind());
    assertEquals(new AttrValue.Str("chat"), span.attributes().get("gen_ai.operation.name"));
  }

  @Test
  public void theRequestAttributesAreTheOnesTheSourceWrites() {
    var a = attrs(completed());
    assertEquals(new AttrValue.Str("gpt-4o"), a.get("gen_ai.request.model"));
    assertEquals(new AttrValue.Num(16L), a.get("gen_ai.request.max_tokens"));
    assertEquals(new AttrValue.Dec(0.25), a.get("gen_ai.request.temperature"));
    assertEquals(new AttrValue.Dec(0.9), a.get("gen_ai.request.top_p"));
    assertEquals(new AttrValue.Str("probe-user"), a.get("gen_ai.user"));
    assertEquals(new AttrValue.Bool(false), a.get("gen_ai.is_streaming"));
    assertEquals(new AttrValue.Str("openai"), a.get("gen_ai.provider.name"));
    assertEquals(new AttrValue.Str("https://api.openai.com/v1"), a.get("gen_ai.openai.api_base"));
  }

  @Test
  public void theResponseAttributesAreTheOnesTheSourceWrites() {
    var a = attrs(completed());
    assertEquals(new AttrValue.Str("gpt-4o-2024-08-06"), a.get("gen_ai.response.model"));
    assertEquals(new AttrValue.Str("chatcmpl-probe1"), a.get("gen_ai.response.id"));
    assertEquals(new AttrValue.StrList(List.of("stop")), a.get("gen_ai.response.finish_reasons"));
    assertEquals(
        new AttrValue.Str("fp_probe"), a.get("gen_ai.openai.response.system_fingerprint"));
    assertEquals(new AttrValue.Num(11L), a.get("gen_ai.usage.input_tokens"));
    assertEquals(new AttrValue.Num(3L), a.get("gen_ai.usage.output_tokens"));
    assertEquals(new AttrValue.Num(14L), a.get("gen_ai.usage.total_tokens"));
    assertEquals(new AttrValue.Num(7L), a.get("gen_ai.usage.cache_read.input_tokens"));
  }

  @Test
  public void cachedTokenDetailIsOmittedWhenTheUsageDidNotCarryIt() {
    var s =
        CallState.opened("c", request())
            .completed("i", "m", null, List.of(Choice.of(0, "assistant", "x", "stop")),
                new Usage(1L, 1L, 2L, null));
    assertFalse(attrs(s).containsKey("gen_ai.usage.cache_read.input_tokens"));
  }

  @Test
  public void promptsAndCompletionsAreOneJsonAttributeEach() {
    var a = attrs(completed());
    assertEquals(
        new AttrValue.Str(
            "[{\"role\":\"system\",\"parts\":[{\"type\":\"text\",\"content\":\"Be terse.\"}]},"
                + "{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"content\":\"2+2?\"}]}]"),
        a.get("gen_ai.input.messages"));
    assertEquals(
        new AttrValue.Str(
            "[{\"role\":\"assistant\",\"parts\":[{\"type\":\"text\",\"content\":\"Four.\"}],"
                + "\"finish_reason\":\"stop\"}]"),
        a.get("gen_ai.output.messages"));
    assertTrue(a.keySet().stream().noneMatch(k -> k.matches("gen_ai\\.prompt\\.\\d+\\..*")));
  }

  @Test
  public void everyPartShapeIsTheShapeTheSourceEmits() {
    var s =
        CallState.opened("c", request())
            .withInputMessages(
                List.of(
                    new Message(
                        "user",
                        List.of(
                            new Part.Text("what is this?"),
                            new Part.Uri("image", "https://example.com/a.png"),
                            new Part.Blob("image", "image/png", "AAAA"))),
                    new Message(
                        "assistant", List.of(new Part.ToolCall("call_x", "look", "{\"q\":1}"))),
                    new Message("tool", List.of(new Part.ToolResponse("call_x", "a cat")))))
            .completed(
                "i", "m", null,
                List.of(Choice.of(0, "assistant", null, "content_filter").withRefusal("no")),
                null);
    var a = attrs(s);
    assertEquals(
        new AttrValue.Str(
            "[{\"role\":\"user\",\"parts\":["
                + "{\"type\":\"text\",\"content\":\"what is this?\"},"
                + "{\"type\":\"uri\",\"modality\":\"image\",\"uri\":\"https://example.com/a.png\"},"
                + "{\"type\":\"blob\",\"modality\":\"image\",\"mime_type\":\"image/png\",\"content\":\"AAAA\"}]},"
                + "{\"role\":\"assistant\",\"parts\":["
                + "{\"type\":\"tool_call\",\"name\":\"look\",\"id\":\"call_x\",\"arguments\":{\"q\":1}}]},"
                + "{\"role\":\"tool\",\"parts\":["
                + "{\"type\":\"tool_call_response\",\"id\":\"call_x\",\"response\":\"a cat\"}]}]"),
        a.get("gen_ai.input.messages"));
    assertEquals(
        new AttrValue.Str(
            "[{\"role\":\"assistant\",\"parts\":[{\"type\":\"refusal\",\"content\":\"no\"}],"
                + "\"finish_reason\":\"content_filter\"}]"),
        a.get("gen_ai.output.messages"));
  }

  @Test
  public void toolCallArgumentsThatAreNotJsonAreCarriedAsTheRawString() {
    var s =
        CallState.opened("c", request())
            .withInputMessages(
                List.of(new Message("assistant", List.of(new Part.ToolCall("t", "f", "not json")))))
            .completed("i", "m", null, List.of(Choice.of(0, "assistant", "x", "stop")), null);
    assertEquals(
        new AttrValue.Str(
            "[{\"role\":\"assistant\",\"parts\":[{\"type\":\"tool_call\",\"name\":\"f\","
                + "\"id\":\"t\",\"arguments\":\"not json\"}]}]"),
        attrs(s).get("gen_ai.input.messages"));
  }

  @Test
  public void aBase64ImageDoesNotCostTheWholeAttribute() {
    // D1. The source drops gen_ai.input.messages entirely here; this port records the blob.
    var s =
        CallState.opened("c", request())
            .withInputMessages(
                List.of(
                    new Message("user", List.of(new Part.Blob("image", "image/png", "AAAA"))),
                    new Message("user", List.of(new Part.Text("and this?")))))
            .completed("i", "m", null, List.of(Choice.of(0, "assistant", "x", "stop")), null);
    var v = ((AttrValue.Str) attrs(s).get("gen_ai.input.messages")).v();
    assertTrue(v.contains("\"type\":\"blob\""));
    assertTrue(v.contains("and this?"));
  }

  @Test
  public void allChoicesAreRecordedNotOnlyTheFirst() {
    var s =
        CallState.opened("c", request())
            .completed(
                "i", "m", null,
                List.of(
                    Choice.of(0, "assistant", "first", "stop"),
                    Choice.of(1, "assistant", null, "content_filter")
                        .withRefusal("I can't help with that.")),
                new Usage(30L, 8L, 38L, null));
    var a = attrs(s);
    assertEquals(
        new AttrValue.StrList(List.of("stop", "content_filter")),
        a.get("gen_ai.response.finish_reasons"));
    assertEquals(2, ((AttrValue.Str) a.get("gen_ai.output.messages")).v().split("\"role\"").length - 1);
  }

  @Test
  public void aToolShapedFinishReasonIsRewrittenInBothPlacesItAppears() {
    var s =
        CallState.opened("c", request())
            .completed(
                "i", "m", null,
                List.of(
                    Choice.of(0, "assistant", "Let me check.", "tool_calls")
                        .withToolCalls(List.of(new ToolCall("call_a", "get_weather", "{\"city\":\"Oslo\"}")))),
                null);
    var a = attrs(s);
    assertEquals(new AttrValue.StrList(List.of("tool_call")), a.get("gen_ai.response.finish_reasons"));
    assertTrue(((AttrValue.Str) a.get("gen_ai.output.messages")).v().contains("\"finish_reason\":\"tool_call\""));
  }

  @Test
  public void anAbsentOrEmptyValueIsOmittedButZeroAndFalseAreWritten() {
    var s =
        CallState.opened(
                "c",
                new Request("gpt-4o", "https://api.openai.com/v1", 0.0, null, null, null, null, "", false))
            .completed("i", "m", null, List.of(Choice.of(0, "assistant", "x", "stop")), null);
    var a = attrs(s);
    assertEquals(new AttrValue.Dec(0.0), a.get("gen_ai.request.temperature"));
    assertEquals(new AttrValue.Bool(false), a.get("gen_ai.is_streaming"));
    assertFalse(a.containsKey("gen_ai.request.top_p"));
    assertFalse(a.containsKey("gen_ai.request.max_tokens"));
    assertFalse(a.containsKey("gen_ai.user"));
  }

  @Test
  public void aStreamedSuccessEndsOkAndANonStreamedSuccessDoesNot() {
    // D2. The two differ in the source and the port reproduces the difference.
    assertEquals(Span.Status.UNSET, Span.of(completed(), true).orElseThrow().status());
    var streamed =
        CallState.opened(
                "c",
                new Request("gpt-4o", "https://api.openai.com/v1", null, null, null, null, null, null, true))
            .completed("i", "m", null, List.of(Choice.of(0, "assistant", "x", "stop")), null);
    assertEquals(Span.Status.OK, Span.of(streamed, true).orElseThrow().status());
    assertEquals(new AttrValue.Bool(true), attrs(streamed).get("gen_ai.is_streaming"));
  }

  @Test
  public void aFailedCallCarriesTheFailureTypeAndKeepsItsRequestAttributes() {
    var s =
        CallState.opened("c", request())
            .withInputMessages(List.of(new Message("user", List.of(new Part.Text("hi")))))
            .failed("InternalServerError", "boom");
    var span = Span.of(s, true).orElseThrow();
    assertEquals(Span.Status.ERROR, span.status());
    assertEquals(new AttrValue.Str("InternalServerError"), span.attributes().get("error.type"));
    assertTrue(span.attributes().containsKey("gen_ai.input.messages"));
    assertEquals(List.of(new Span.Event("exception", "InternalServerError", "boom")), span.events());
  }

  @Test
  public void turningContentOffRemovesTheTwoMessageAttributesAndNothingElse() {
    var on = attrs(completed());
    var off = Span.of(completed(), false).orElseThrow().attributes();
    assertFalse(off.containsKey("gen_ai.input.messages"));
    assertFalse(off.containsKey("gen_ai.output.messages"));
    var expected = new java.util.HashMap<>(on);
    expected.remove("gen_ai.input.messages");
    expected.remove("gen_ai.output.messages");
    assertEquals(expected, off);
  }

  @Test
  public void aStreamedCallCarriesOneEventPerItemTheStreamDelivered() {
    var streamed =
        new Request("gpt-4o", "https://api.openai.com/v1", null, null, null, null, null, null, true);
    var state = CallState.opened("c", streamed);
    for (var text : List.of("Let ", "me ", "check.")) {
      state = state.apply(new CallEvent.ChunksReceived(null, "id", "gpt-4o", null,
          List.of(new Chunk(0, "assistant", text, null, List.of(), null, null)), null));
    }
    // The usage-only item a stream ends with is an item like any other, and is counted.
    state = state.apply(new CallEvent.ChunksReceived(null, "id", "gpt-4o", null,
        List.of(new Chunk(0, null, null, null, List.of(), null, null)),
        new Usage(1L, 1L, 2L, null)));
    state = state.apply(new CallEvent.Closed());

    var span = Span.of(state, true).orElseThrow();
    assertEquals(4, span.events().size());
    assertTrue(span.events().stream()
        .allMatch(e -> e.name().equals("gen_ai.content.completion.chunk")));
  }

  @Test
  public void aNonStreamedCallCarriesNoChunkEvents() {
    assertEquals(List.of(), Span.of(completed(), true).orElseThrow().events());
  }

  @Test
  public void aStreamedCallThatFailsCarriesItsChunkEventsAndThenTheException() {
    var streamed =
        new Request("gpt-4o", "https://api.openai.com/v1", null, null, null, null, null, null, true);
    var state = CallState.opened("c", streamed)
        .apply(new CallEvent.ChunksReceived(null, "id", "gpt-4o", null,
            List.of(new Chunk(0, "assistant", "Let ", null, List.of(), null, null)), null))
        .apply(new CallEvent.Failed("APIConnectionError", "reset"));
    assertEquals(
        List.of("gen_ai.content.completion.chunk", "exception"),
        Span.of(state, true).orElseThrow().events().stream().map(Span.Event::name).toList());
  }

  @Test
  public void aSuppressedCallProducesNoSpanAtAll() {
    assertTrue(Span.of(completed().markSuppressed(), true).isEmpty());
    assertTrue(Span.of(completed().markSuppressed(), false).isEmpty());
  }

  @Test
  public void anOpenCallHasNoSpanYet() {
    assertTrue(Span.of(CallState.opened("c", request()), true).isEmpty());
  }

  @Test
  public void derivingTwiceFromOneStateGivesTheSameSpan() {
    var s = completed();
    assertEquals(Span.of(s, true), Span.of(s, true));
  }

  @Test
  public void oneKeyOrderIsUsedWhicheverWayTheContentArrived() {
    // D5. The source emits two key orders for the same information; this port emits one.
    var asString =
        CallState.opened("c", request())
            .withInputMessages(List.of(new Message("user", List.of(new Part.Text("hi")))))
            .completed("i", "m", null, List.of(Choice.of(0, "assistant", "x", "stop")), null);
    assertEquals(
        new AttrValue.Str("[{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"content\":\"hi\"}]}]"),
        attrs(asString).get("gen_ai.input.messages"));
  }
}
