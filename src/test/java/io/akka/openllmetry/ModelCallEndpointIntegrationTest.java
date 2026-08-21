package io.akka.openllmetry;

import static org.junit.jupiter.api.Assertions.*;

import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.openllmetry.api.ModelCallEndpoint;
import io.akka.openllmetry.domain.Chunk;
import io.akka.openllmetry.domain.Message;
import io.akka.openllmetry.domain.Part;
import io.akka.openllmetry.domain.Request;
import io.akka.openllmetry.domain.Usage;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 rules 11 and 18 and decision D4, over HTTP against a started runtime — the port's only
 * route in from outside its own tests.
 */
class ModelCallEndpointIntegrationTest extends TestKitSupport {

  private static final Request STREAMED =
      new Request("gpt-4o", "https://api.openai.com/v1", null, null, null, null, null, null, true);

  private void open(String id) {
    httpClient
        .POST("/calls/" + id + "/open")
        .withRequestBody(
            new ModelCallEndpoint.OpenRequest(
                STREAMED, List.of(new Message("user", List.of(new Part.Text("weather in Oslo?"))))))
        .invoke();
  }

  private void send(String id, String deliveryId, String content, String finishReason, Usage usage) {
    httpClient
        .POST("/calls/" + id + "/chunks")
        .withRequestBody(
            new ModelCallEndpoint.ChunkRequest(
                deliveryId,
                "chatcmpl-http", "gpt-4o-2024-08-06", null,
                List.of(new Chunk(0, "assistant", content, null, List.of(), finishReason, null)),
                usage))
        .invoke();
  }

  private ModelCallEndpoint.SpanView spanOf(String id) {
    return httpClient
        .GET("/calls/" + id + "/span")
        .responseBodyAs(ModelCallEndpoint.SpanView.class)
        .invoke()
        .body();
  }

  @Test
  void aStreamRecordedOverHttpBecomesTheSpanTheSpecificationDescribesOnStartedRuntime() {
    var id = "http-1";
    open(id);
    send(id, "d1", "Let ", null, null);
    send(id, "d2", "me check.", "stop", new Usage(20L, 9L, 29L, null));

    assertEquals(
        StatusCodes.NO_CONTENT,
        httpClient.GET("/calls/" + id + "/span").invoke().httpResponse().status());

    httpClient.POST("/calls/" + id + "/close").invoke();
    var span = spanOf(id);
    assertEquals("openai.chat", span.name());
    assertEquals("CLIENT", span.kind());
    assertEquals("OK", span.status());
    assertEquals("gpt-4o", span.attributes().get("gen_ai.request.model"));
    assertEquals("gpt-4o-2024-08-06", span.attributes().get("gen_ai.response.model"));
    assertEquals(29, ((Number) span.attributes().get("gen_ai.usage.total_tokens")).longValue());
    assertEquals(List.of("stop"), span.attributes().get("gen_ai.response.finish_reasons"));
    assertTrue(span.attributes().get("gen_ai.output.messages").toString().contains("Let me check."));
  }

  @Test
  void aReaderThatComesBackLateIsServedEveryDeltaAlreadyRecordedOnStartedRuntime() {
    // D4. The source has no answer here — it hands finished spans to an exporter and has no
    // notion of a consumer. A reader here catches up from the record, numbered, so it can say
    // what it has already seen.
    var id = "http-2";
    open(id);
    var deltas = List.of("Let ", "me ", "check.");
    for (int i = 0; i < deltas.size(); i++) {
      send(id, "d" + i, deltas.get(i), null, null);
    }
    var progress =
        httpClient
            .GET("/calls/" + id + "/progress")
            .responseBodyAs(ModelCallEndpoint.ProgressEntry[].class)
            .invoke()
            .body();
    assertEquals(3, progress.length);
    assertEquals(List.of(0L, 1L, 2L),
        java.util.Arrays.stream(progress).map(ModelCallEndpoint.ProgressEntry::sequence).toList());
    assertEquals("Let me check.",
        java.util.Arrays.stream(progress).map(ModelCallEndpoint.ProgressEntry::text)
            .reduce("", String::concat));
  }

  @Test
  void aFailedCallIsReadableAsAnErrorSpanOverHttpOnStartedRuntime() {
    var id = "http-3";
    open(id);
    send(id, "d1", "Let ", null, null);
    httpClient
        .POST("/calls/" + id + "/fail")
        .withRequestBody(new ModelCallEndpoint.FailRequest("APIConnectionError", "connection reset"))
        .invoke();

    var span = spanOf(id);
    assertEquals("ERROR", span.status());
    assertEquals("APIConnectionError", span.attributes().get("error.type"));
    assertTrue(span.attributes().containsKey("gen_ai.input.messages"));
    // One event for the delta that was recorded, then the exception the call ended on.
    assertEquals(
        List.of("gen_ai.content.completion.chunk", "exception"),
        span.events().stream().map(io.akka.openllmetry.domain.Span.Event::name).toList());
  }

  @Test
  void aSuppressedCallNeverYieldsASpanOverHttpOnStartedRuntime() {
    var id = "http-4";
    open(id);
    httpClient.POST("/calls/" + id + "/suppress").invoke();
    httpClient.POST("/calls/" + id + "/close").invoke();
    assertEquals(
        StatusCodes.NO_CONTENT,
        httpClient.GET("/calls/" + id + "/span").invoke().httpResponse().status());
  }
}
