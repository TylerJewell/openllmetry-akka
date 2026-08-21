package io.akka.openllmetry.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.CommandException;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpException;
import akka.javasdk.http.HttpResponses;
import io.akka.openllmetry.application.ModelCallEntity;
import io.akka.openllmetry.domain.AttrValue;
import io.akka.openllmetry.domain.Chunk;
import io.akka.openllmetry.domain.Message;
import io.akka.openllmetry.domain.Request;
import io.akka.openllmetry.domain.Span;
import io.akka.openllmetry.domain.Usage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The capability's own surface: record a model call as it happens, and read the span it became.
 *
 * <p>The span is rendered as plain attribute values rather than as the tagged union the journal
 * holds, because a caller comparing this against a span from any other OpenTelemetry producer is
 * comparing values, not this port's encoding of them.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@HttpEndpoint("/calls")
public class ModelCallEndpoint {

  private final ComponentClient componentClient;

  public ModelCallEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public record OpenRequest(Request request, List<Message> inputMessages) {}

  /**
   * {@code deliveryId} identifies this batch. A caller that retries reuses it, so a redelivery is
   * recognised rather than appended a second time.
   */
  public record ChunkRequest(
      String deliveryId,
      String responseId,
      String responseModel,
      String systemFingerprint,
      List<Chunk> chunks,
      Usage usage) {}

  public record FailRequest(String type, String message) {}

  public record SpanView(
      String name, String kind, String status, Map<String, Object> attributes,
      List<Span.Event> events) {}

  public record ProgressEntry(long sequence, String text) {}

  @Post("/{callId}/open")
  public HttpResponse open(String callId, OpenRequest body) {
    return mapErrors(
        () -> {
          componentClient
              .forEventSourcedEntity(callId)
              .method(ModelCallEntity::open)
              .invoke(new ModelCallEntity.Open(body.request(), body.inputMessages()));
          return HttpResponses.created();
        });
  }

  @Post("/{callId}/chunks")
  public HttpResponse chunks(String callId, ChunkRequest body) {
    return mapErrors(
        () -> {
          componentClient
              .forEventSourcedEntity(callId)
              .method(ModelCallEntity::chunk)
              .invoke(new ModelCallEntity.Chunks(
                  body.deliveryId(), body.responseId(), body.responseModel(),
                  body.systemFingerprint(), body.chunks(), body.usage()));
          return HttpResponses.ok();
        });
  }

  @Post("/{callId}/close")
  public HttpResponse close(String callId) {
    return mapErrors(
        () -> {
          componentClient.forEventSourcedEntity(callId).method(ModelCallEntity::close).invoke();
          return HttpResponses.ok();
        });
  }

  @Post("/{callId}/fail")
  public HttpResponse fail(String callId, FailRequest body) {
    return mapErrors(
        () -> {
          componentClient
              .forEventSourcedEntity(callId)
              .method(ModelCallEntity::fail)
              .invoke(new ModelCallEntity.Failed(body.type(), body.message()));
          return HttpResponses.ok();
        });
  }

  @Post("/{callId}/suppress")
  public HttpResponse suppress(String callId) {
    return mapErrors(
        () -> {
          componentClient.forEventSourcedEntity(callId).method(ModelCallEntity::suppress).invoke();
          return HttpResponses.ok();
        });
  }

  /** 204 while the call has no span yet — open, or suppressed and never going to have one. */
  @Get("/{callId}/span")
  public HttpResponse span(String callId) {
    var span =
        componentClient.forEventSourcedEntity(callId).method(ModelCallEntity::span).invoke();
    return span.map(s -> HttpResponses.ok(view(s))).orElseGet(HttpResponses::noContent);
  }

  /**
   * SPEC-001 decision D4 — a reader that reconnects is served from the record, so nothing recorded
   * before the drop is missed. Every delta carries its sequence number, so a reader can say what it
   * has already seen and see where the retained window starts.
   */
  @Get("/{callId}/progress")
  public List<ProgressEntry> progress(String callId) {
    return componentClient
        .forEventSourcedEntity(callId)
        .method(ModelCallEntity::progress)
        .invoke()
        .stream()
        .map(p -> new ProgressEntry(p.sequence(), p.text()))
        .toList();
  }

  /** Without this a rejected command reaches the caller as a bare 400 carrying the raw message. */
  private static HttpResponse mapErrors(Supplier<HttpResponse> call) {
    try {
      return call.get();
    } catch (CommandException e) {
      throw HttpException.badRequest(e.getMessage());
    }
  }

  private static SpanView view(Span span) {
    var attributes = new LinkedHashMap<String, Object>();
    span.attributes().forEach((k, v) -> attributes.put(k, plain(v)));
    return new SpanView(span.name(), span.kind().name(), span.status().name(), attributes,
        span.events());
  }

  private static Object plain(AttrValue value) {
    return switch (value) {
      case AttrValue.Str v -> v.v();
      case AttrValue.Num v -> v.v();
      case AttrValue.Dec v -> v.v();
      case AttrValue.Bool v -> v.v();
      case AttrValue.StrList v -> v.v();
    };
  }
}
