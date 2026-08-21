package io.akka.openllmetry.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SPEC-001 rule 3 and decision D5 — the JSON carried by the two message attributes.
 *
 * <p>Keys are written in one order for every part of a given kind, whichever way the content
 * reached the port. Insertion-ordered maps are what makes that true, so the maps here are
 * {@link LinkedHashMap} deliberately.
 */
public final class Messages {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static String input(List<Message> messages) {
    var out = new ArrayList<Map<String, Object>>();
    for (var m : messages) {
      var entry = new LinkedHashMap<String, Object>();
      entry.put("role", m.role());
      entry.put("parts", m.parts().stream().map(Messages::part).toList());
      out.add(entry);
    }
    return write(out);
  }

  public static String output(List<Choice> choices) {
    var out = new ArrayList<Map<String, Object>>();
    for (var c : choices) {
      var parts = new ArrayList<Map<String, Object>>();
      if (c.content() != null) parts.add(part(new Part.Text(c.content())));
      if (c.refusal() != null) parts.add(part(new Part.Refusal(c.refusal())));
      if (c.reasoning() != null) parts.add(part(new Part.Reasoning(c.reasoning())));
      for (var tc : c.toolCalls()) {
        parts.add(part(new Part.ToolCall(tc.id(), tc.name(), tc.arguments())));
      }
      var entry = new LinkedHashMap<String, Object>();
      entry.put("role", c.role() == null ? "assistant" : c.role());
      entry.put("parts", parts);
      entry.put("finish_reason", c.finishReason() == null ? "" : c.finishReason());
      out.add(entry);
    }
    return write(out);
  }

  private static Map<String, Object> part(Part p) {
    var m = new LinkedHashMap<String, Object>();
    switch (p) {
      case Part.Text t -> {
        m.put("type", "text");
        m.put("content", t.content());
      }
      case Part.Uri u -> {
        m.put("type", "uri");
        m.put("modality", u.modality());
        m.put("uri", u.uri());
      }
      case Part.Blob b -> {
        m.put("type", "blob");
        m.put("modality", b.modality());
        m.put("mime_type", b.mimeType());
        m.put("content", b.content());
      }
      case Part.ToolCall t -> {
        m.put("type", "tool_call");
        m.put("name", t.name());
        m.put("id", t.id());
        m.put("arguments", arguments(t.arguments()));
      }
      case Part.ToolResponse t -> {
        m.put("type", "tool_call_response");
        m.put("id", t.id());
        m.put("response", t.response());
      }
      case Part.Refusal r -> {
        m.put("type", "refusal");
        m.put("content", r.content());
      }
      case Part.Reasoning r -> {
        m.put("type", "reasoning");
        m.put("content", r.content());
      }
    }
    return m;
  }

  /** Argument text that parses as JSON is carried as JSON; anything else stays a string. */
  private static Object arguments(String raw) {
    if (raw == null) return null;
    try {
      JsonNode node = MAPPER.readTree(raw);
      return node.isContainerNode() ? node : raw;
    } catch (JsonProcessingException e) {
      return raw;
    }
  }

  private static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("message content could not be written as JSON", e);
    }
  }

  private Messages() {}
}
