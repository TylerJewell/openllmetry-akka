package io.akka.openllmetry;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.openllmetry.domain.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Step e, part 1 — do both systems give the same answers to the same inputs.
 *
 * <p>Reads the same {@code bench/workloads.json} the source runner reads, drives this port's
 * derivation over it, writes {@code port-answers.json} beside the source's, and compares
 * field by field. Two normalisations are applied to both sides equally and to neither alone:
 * JSON-valued attributes are compared as parsed values with their keys sorted, and the base
 * URL is compared without a trailing slash. Everything else is compared exactly.
 */
class BenchAnswersTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Path OUT =
      Path.of("..", "openllmetry-port", "bench", "port-answers.json");

  /**
   * The one field the two sides are expected to differ on, and where. SPEC-001 D1: the source
   * drops the whole attribute when a prompt carries an inline image; this port records it.
   */
  private static final List<String> EXPECTED_DIFFERENCES =
      List.of("nonstreamed-inline-image", "stream-arrival-order-distinct");

  private static JsonNode resource(String name) throws IOException {
    try (InputStream in = BenchAnswersTest.class.getResourceAsStream("/bench/" + name)) {
      assertNotNull(in, name + " is missing from the test resources");
      return MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void everyWorkloadGivesTheSameAnswerOnBothSidesExceptTheOneDecidedDifferently()
      throws IOException {
    var workloads = resource("workloads.json");
    var source = resource("source-answers.json").get("answers");

    var answers = MAPPER.createObjectNode();
    for (var w : workloads) {
      answers.set(w.get("name").asText(), run(w));
    }

    var timing = timing(workloads);
    var out = MAPPER.createObjectNode();
    out.set("answers", answers);
    out.set("timing", timing);
    Files.createDirectories(OUT.getParent());
    Files.writeString(OUT, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out));

    var disagreements = new ArrayList<String>();
    int compared = 0;
    int steps = 0;
    for (var name : (Iterable<String>) () -> source.fieldNames()) {
      var left = source.get(name);
      var right = answers.get(name);
      assertNotNull(right, "port produced no answers for " + name);
      assertEquals(left.size(), right.size(), name + ": the two sides ran different work");
      for (int i = 0; i < left.size(); i++) {
        steps++;
        var l = left.get(i);
        var r = right.get(i);
        for (var field : (Iterable<String>) () -> l.fieldNames()) {
          // The source re-raises the API's own failure to its caller; the port is told that a
          // call failed rather than discovering it, so on a failure workload the two have
          // nothing to compare. The arrival-order workloads are the exception: there `raised`
          // records a fault in the source's own accumulation, and that is an answer.
          if (field.equals("raised") && !name.startsWith("stream-arrival-order")) continue;
          compared++;
          var lv = l.get(field);
          var rv = r.get(field);
          if (!agree(lv, rv)) {
            disagreements.add(name + " step " + i + " " + field + ": source=" + lv + " port=" + rv);
          }
        }
        for (var field : (Iterable<String>) () -> r.fieldNames()) {
          if (l.get(field) == null) {
            disagreements.add(name + " step " + i + " " + field + ": source=<absent> port=" + r.get(field));
          }
        }
      }
    }

    System.out.println("bench: " + source.size() + " workloads, " + steps + " steps, "
        + compared + " field comparisons");
    disagreements.forEach(d -> System.out.println("  differs: " + d));

    var unexpected = disagreements.stream()
        .filter(d -> EXPECTED_DIFFERENCES.stream().noneMatch(e -> d.startsWith(e + " ")))
        .toList();
    assertEquals(List.of(), unexpected,
        "the two sides disagree somewhere other than the decision SPEC-001 D1 records");
    for (var expected : EXPECTED_DIFFERENCES) {
      assertTrue(
          disagreements.stream().anyMatch(d -> d.startsWith(expected + " ")),
          "the two sides are recorded as differing on " + expected + " and did not, so either "
              + "the source changed or this comparison has stopped comparing");
    }
  }

  /**
   * Jackson types an integer literal by its width, so 16 read from the source's file and 16
   * written from a Java long are different nodes carrying the same number. Numbers are
   * therefore compared by value; everything else by identity of the node.
   */
  private static boolean agree(JsonNode left, JsonNode right) {
    if (right == null) return false;
    if (left.isNumber() && right.isNumber()) {
      return left.decimalValue().compareTo(right.decimalValue()) == 0;
    }
    return left.equals(right);
  }

  // --- driving this port over the workload -------------------------------------------------

  private ArrayNode run(JsonNode w) {
    return switch (w.get("kind").asText()) {
      case "response" -> single(w);
      case "response-sequence" -> sequence(w);
      case "stream" -> stream(w);
      case "stream-orders" -> orders(w);
      case "failure" -> failure(w);
      default -> throw new IllegalArgumentException("unknown workload kind");
    };
  }

  private ArrayNode single(JsonNode w) {
    var out = MAPPER.createArrayNode();
    if (w.path("suppressed").asBoolean(false)) {
      out.add(record(0, Optional.empty(), null));
      return out;
    }
    var state = opened(w, w.get("baseUrl").asText(), model(w))
        .completed(
            w.get("response").get("id").asText(),
            w.get("response").get("model").asText(),
            text(w.get("response").get("system_fingerprint")),
            choices(w.get("response").get("choices")),
            usage(w.get("response").get("usage")));
    out.add(record(0, Span.of(state, w.path("traceContent").asBoolean(true)), null));
    return out;
  }

  private ArrayNode sequence(JsonNode w) {
    var out = MAPPER.createArrayNode();
    var models = w.get("models");
    for (int i = 0; i < w.get("baseUrls").size(); i++) {
      var m = models == null ? model(w) : models.get(i).asText();
      var state = opened(w, w.get("baseUrls").get(i).asText(), m)
          .completed(
              w.get("response").get("id").asText(),
              w.get("response").get("model").asText(),
              text(w.get("response").get("system_fingerprint")),
              choices(w.get("response").get("choices")),
              usage(w.get("response").get("usage")));
      out.add(record(i, Span.of(state, true), null));
    }
    return out;
  }

  private ArrayNode stream(JsonNode w) {
    var out = MAPPER.createArrayNode();
    var state = opened(w, w.get("baseUrl").asText(), model(w));
    int step = 0;
    for (var c : w.get("chunks")) {
      state = state.apply(chunksReceived(w, c));
      out.add(record(step++, Span.of(state, true), null));
    }
    state = state.apply(new CallEvent.Closed());
    out.add(record(step, Span.of(state, true), null));
    return out;
  }

  private ArrayNode orders(JsonNode w) {
    var deltas = new ArrayList<JsonNode>();
    w.get("rows").forEach(deltas::add);
    var out = MAPPER.createArrayNode();
    int step = 0;
    for (var order : rotations(deltas)) {
      var state = opened(w, w.get("baseUrl").asText(), model(w));
      var delivered = new StringBuilder();
      for (var d : order) {
        state = state.apply(chunksReceived(w, d));
        delivered.append(d.path("content").asText(""));
      }
      state = state.apply(chunksReceived(w, w.get("finish")));
      state = state.apply(new CallEvent.Closed());
      var rec = record(step++, Span.of(state, true), delivered.toString());
      var indices = MAPPER.createArrayNode();
      order.forEach(d -> indices.add(d.path("index").asInt(0)));
      rec.set("deliveredIndices", indices);
      rec.putNull("raised");
      out.add(rec);
    }
    return out;
  }

  private ArrayNode failure(JsonNode w) {
    var out = MAPPER.createArrayNode();
    var state = opened(w, w.get("baseUrl").asText(), model(w))
        .failed(w.get("expectedType").asText(), "boom");
    out.add(record(0, Span.of(state, true), null));
    return out;
  }

  private static List<List<JsonNode>> rotations(List<JsonNode> items) {
    var out = new ArrayList<List<JsonNode>>();
    for (int i = 0; i < items.size(); i++) {
      var rotated = new ArrayList<JsonNode>(items.subList(i, items.size()));
      rotated.addAll(items.subList(0, i));
      out.add(rotated);
    }
    var reversed = new ArrayList<>(items);
    java.util.Collections.reverse(reversed);
    out.add(reversed);
    return out;
  }

  private CallEvent.ChunksReceived chunksReceived(JsonNode w, JsonNode c) {
    Usage u = c.has("usage") ? usage(c.get("usage")) : null;
    // A usage-only item is still an item in the stream, so it is delivered as an empty chunk
    // rather than dropped: the source counts it.
    var chunks = List.of(chunk(c));
    return new CallEvent.ChunksReceived(
        null, text(w.get("responseId")), text(w.get("responseModel")), null, chunks, u);
  }

  private static Chunk chunk(JsonNode c) {
    var calls = new ArrayList<Chunk.ToolCallDelta>();
    for (var t : c.path("tool_calls")) {
      calls.add(new Chunk.ToolCallDelta(
          t.get("index").asInt(), text(t.get("id")), text(t.get("name")),
          text(t.get("arguments"))));
    }
    return new Chunk(
        c.path("index").asInt(0),
        text(c.get("role")),
        c.has("content") ? c.get("content").asText() : null,
        null,
        calls,
        text(c.get("finish_reason")),
        null);
  }

  private CallState opened(JsonNode w, String baseUrl, String model) {
    var r = w.get("request");
    var request = new Request(
        model,
        baseUrl,
        r.has("temperature") ? r.get("temperature").asDouble() : null,
        r.has("top_p") ? r.get("top_p").asDouble() : null,
        r.has("max_tokens") ? r.get("max_tokens").asLong() : null,
        r.has("frequency_penalty") ? r.get("frequency_penalty").asDouble() : null,
        r.has("presence_penalty") ? r.get("presence_penalty").asDouble() : null,
        text(r.get("user")),
        r.path("stream").asBoolean(false));
    return CallState.opened("bench", request).withInputMessages(messages(w.get("messages")));
  }

  private static String model(JsonNode w) {
    return w.get("request").get("model").asText();
  }

  private static List<Message> messages(JsonNode nodes) {
    var out = new ArrayList<Message>();
    for (var m : nodes) {
      var role = m.get("role").asText();
      var parts = new ArrayList<Part>();
      var content = m.get("content");
      if (role.equals("tool")) {
        parts.add(new Part.ToolResponse(text(m.get("tool_call_id")), text(content)));
      } else {
        parts.addAll(contentParts(content));
        for (var t : m.path("tool_calls")) {
          parts.add(new Part.ToolCall(
              text(t.get("id")),
              text(t.get("function").get("name")),
              text(t.get("function").get("arguments"))));
        }
      }
      out.add(new Message(role, List.copyOf(parts)));
    }
    return List.copyOf(out);
  }

  private static List<Part> contentParts(JsonNode content) {
    if (content == null || content.isNull()) return List.of();
    if (content.isTextual()) return List.of(new Part.Text(content.asText()));
    var out = new ArrayList<Part>();
    for (var block : content) {
      var type = block.get("type").asText();
      if (type.equals("text")) {
        out.add(new Part.Text(block.get("text").asText()));
      } else if (type.equals("image_url")) {
        var url = block.get("image_url").get("url").asText();
        if (url.startsWith("data:")) {
          var header = url.substring(0, url.indexOf(','));
          var mime = header.substring(header.indexOf(':') + 1, header.indexOf(';'));
          out.add(new Part.Blob("image", mime, url.substring(url.indexOf(',') + 1)));
        } else {
          out.add(new Part.Uri("image", url));
        }
      }
    }
    return out;
  }

  private static List<Choice> choices(JsonNode nodes) {
    var out = new ArrayList<Choice>();
    for (var c : nodes) {
      var m = c.get("message");
      var choice = Choice.of(
          c.get("index").asInt(), text(m.get("role")), text(m.get("content")),
          text(c.get("finish_reason")));
      if (m.hasNonNull("refusal")) choice = choice.withRefusal(m.get("refusal").asText());
      var calls = new ArrayList<ToolCall>();
      for (var t : m.path("tool_calls")) {
        calls.add(new ToolCall(text(t.get("id")), text(t.get("function").get("name")),
            text(t.get("function").get("arguments"))));
      }
      if (!calls.isEmpty()) choice = choice.withToolCalls(calls);
      out.add(choice);
    }
    return List.copyOf(out);
  }

  private static Usage usage(JsonNode u) {
    if (u == null || u.isNull()) return null;
    Long cached = null;
    if (u.hasNonNull("prompt_tokens_details")
        && u.get("prompt_tokens_details").hasNonNull("cached_tokens")) {
      cached = u.get("prompt_tokens_details").get("cached_tokens").asLong();
    }
    return new Usage(
        u.hasNonNull("prompt_tokens") ? u.get("prompt_tokens").asLong() : null,
        u.hasNonNull("completion_tokens") ? u.get("completion_tokens").asLong() : null,
        u.hasNonNull("total_tokens") ? u.get("total_tokens").asLong() : null,
        cached);
  }

  private static String text(JsonNode n) {
    return n == null || n.isNull() ? null : n.asText();
  }

  // --- the answer record, in the shape the source runner writes ----------------------------

  private static final List<String> JSON_ATTRIBUTES =
      List.of("gen_ai.input.messages", "gen_ai.output.messages", "gen_ai.tool.definitions");

  private ObjectNode record(int step, Optional<Span> maybe, String delivered) {
    var out = MAPPER.createObjectNode();
    out.put("step", step);
    if (delivered != null) out.put("delivered", delivered);
    if (maybe.isEmpty()) {
      out.put("spanPresent", false);
      return out;
    }
    var span = maybe.get();
    out.put("spanPresent", true);
    out.put("name", span.name());
    out.put("kind", span.kind().name());
    out.put("status", span.status().name());
    var events = MAPPER.createArrayNode();
    span.events().forEach(e -> events.add(e.name()));
    out.set("eventNames", events);
    for (var e : span.attributes().entrySet()) {
      put(out, e.getKey(), e.getValue());
    }
    return out;
  }

  private void put(ObjectNode out, String key, AttrValue value) {
    switch (value) {
      case AttrValue.Str v -> {
        if (JSON_ATTRIBUTES.contains(key)) {
          out.put(key, canonical(v.v()));
        } else if (key.equals("gen_ai.openai.api_base")) {
          out.put(key, v.v().replaceAll("/+$", ""));
        } else {
          out.put(key, v.v());
        }
      }
      case AttrValue.Num v -> out.put(key, v.v());
      case AttrValue.Dec v -> out.put(key, v.v());
      case AttrValue.Bool v -> out.put(key, v.v());
      case AttrValue.StrList v -> {
        var a = MAPPER.createArrayNode();
        v.v().forEach(a::add);
        out.set(key, a);
      }
    }
  }

  /** Python's json.dumps(..., sort_keys=True) with default separators, matched exactly. */
  private String canonical(String json) {
    try {
      return render(MAPPER.readTree(json));
    } catch (IOException e) {
      throw new IllegalStateException("attribute is not JSON: " + json, e);
    }
  }

  private String render(JsonNode node) {
    if (node.isObject()) {
      var sorted = new TreeMap<String, JsonNode>();
      node.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
      var parts = new ArrayList<String>();
      sorted.forEach((k, v) -> parts.add(quote(k) + ": " + render(v)));
      return "{" + String.join(", ", parts) + "}";
    }
    if (node.isArray()) {
      var parts = new ArrayList<String>();
      node.forEach(v -> parts.add(render(v)));
      return "[" + String.join(", ", parts) + "]";
    }
    if (node.isTextual()) return quote(node.asText());
    if (node.isNull()) return "null";
    if (node.isBoolean()) return node.asBoolean() ? "true" : "false";
    return node.asText();
  }

  private static String quote(String s) {
    try {
      return MAPPER.writeValueAsString(s);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  // --- speed ------------------------------------------------------------------------------

  private ObjectNode timing(JsonNode workloads) {
    JsonNode basic = null;
    for (var w : workloads) {
      if (w.get("name").asText().equals("nonstreamed-basic")) basic = w;
    }
    var state = opened(basic, basic.get("baseUrl").asText(), model(basic))
        .completed(
            basic.get("response").get("id").asText(),
            basic.get("response").get("model").asText(),
            text(basic.get("response").get("system_fingerprint")),
            choices(basic.get("response").get("choices")),
            usage(basic.get("response").get("usage")));

    for (int i = 0; i < 20_000; i++) {
      Span.of(state, true);
    }
    int iterations = 100_000;
    long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      Span.of(state, true);
    }
    double micros = (System.nanoTime() - start) / 1_000.0 / iterations;

    var out = MAPPER.createObjectNode();
    out.put("workload", "nonstreamed-basic");
    out.put("iterations", iterations);
    out.put("microsecondsPerDerivation", Math.round(micros * 1000.0) / 1000.0);
    System.out.println("bench: " + out.get("microsecondsPerDerivation").asDouble()
        + " us per derivation over " + iterations);
    return out;
  }
}
