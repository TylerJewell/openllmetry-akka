package io.akka.openllmetry.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A finished span. SPEC-001 rules 1 to 19.
 *
 * <p>{@link #of} is the whole capability: a pure function from what was recorded to the complete
 * set of attributes, so the same state always yields the same span and a call rebuilt from the
 * journal is indistinguishable from one that was never interrupted.
 */
public record Span(
    String name, Kind kind, Status status, Map<String, AttrValue> attributes, List<Event> events) {

  public enum Kind {
    CLIENT
  }

  public enum Status {
    UNSET,
    OK,
    ERROR
  }

  public record Event(String name, String type, String message) {}

  public static final String NAME = "openai.chat";

  public static final String COMPLETION_CHUNK = "gen_ai.content.completion.chunk";

  public static Optional<Span> of(CallState state, boolean traceContent) {
    if (state.suppressed() || state.isOpen()) return Optional.empty();

    var a = new LinkedHashMap<String, AttrValue>();
    var provider = Provider.fromBaseUrl(state.request().baseUrl());

    put(a, "gen_ai.operation.name", "chat");
    put(a, "gen_ai.provider.name", provider);
    put(a, "gen_ai.openai.api_base", state.request().baseUrl());
    put(a, "gen_ai.request.model", Provider.requestModel(provider, state.request().model()));
    put(a, "gen_ai.request.max_tokens", state.request().maxTokens());
    put(a, "gen_ai.request.temperature", state.request().temperature());
    put(a, "gen_ai.request.top_p", state.request().topP());
    put(a, "gen_ai.request.frequency_penalty", state.request().frequencyPenalty());
    put(a, "gen_ai.request.presence_penalty", state.request().presencePenalty());
    put(a, "gen_ai.user", state.request().user());
    a.put("gen_ai.is_streaming", new AttrValue.Bool(state.request().streaming()));

    put(a, "gen_ai.response.model", Provider.responseModel(state.responseModel()));
    put(a, "gen_ai.response.id", state.responseId());
    put(a, "gen_ai.openai.response.system_fingerprint", state.systemFingerprint());

    var reasons = state.choices().stream()
        .map(Choice::finishReason)
        .filter(r -> r != null && !r.isEmpty())
        .toList();
    if (!reasons.isEmpty()) a.put("gen_ai.response.finish_reasons", new AttrValue.StrList(reasons));

    if (state.usage() != null) {
      put(a, "gen_ai.usage.input_tokens", state.usage().inputTokens());
      put(a, "gen_ai.usage.output_tokens", state.usage().outputTokens());
      put(a, "gen_ai.usage.total_tokens", state.usage().totalTokens());
      put(a, "gen_ai.usage.cache_read.input_tokens", state.usage().cacheReadInputTokens());
    }

    if (traceContent) {
      put(a, "gen_ai.input.messages", Messages.input(state.inputMessages()));
      if (!state.choices().isEmpty()) {
        put(a, "gen_ai.output.messages", Messages.output(state.choices()));
      }
    }

    // One event per item the stream delivered, carrying no attributes — including the
    // usage-only item a stream ends with, which is an item like any other.
    var events = new ArrayList<Event>();
    for (long i = 0; i < state.chunksRecorded(); i++) {
      events.add(new Event(COMPLETION_CHUNK, null, null));
    }
    var status = Status.UNSET;
    if (state.outcome() == CallState.Outcome.ERROR) {
      status = Status.ERROR;
      put(a, "error.type", state.failure().type());
      events.add(new Event("exception", state.failure().type(), state.failure().message()));
    } else if (state.request().streaming()) {
      status = Status.OK;
    }

    return Optional.of(new Span(NAME, Kind.CLIENT, status, Map.copyOf(a), List.copyOf(events)));
  }

  /** SPEC-001 rule 6 — null and empty are omitted; zero and false are written. */
  private static void put(Map<String, AttrValue> a, String key, String value) {
    if (value != null && !value.isEmpty()) a.put(key, new AttrValue.Str(value));
  }

  private static void put(Map<String, AttrValue> a, String key, Long value) {
    if (value != null) a.put(key, new AttrValue.Num(value));
  }

  private static void put(Map<String, AttrValue> a, String key, Double value) {
    if (value != null) a.put(key, new AttrValue.Dec(value));
  }
}
