package io.akka.openllmetry.domain;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** One piece of a message's content. SPEC-001 rule 3. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "k")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Part.Text.class, name = "text"),
  @JsonSubTypes.Type(value = Part.Uri.class, name = "uri"),
  @JsonSubTypes.Type(value = Part.Blob.class, name = "blob"),
  @JsonSubTypes.Type(value = Part.ToolCall.class, name = "tool_call"),
  @JsonSubTypes.Type(value = Part.ToolResponse.class, name = "tool_call_response"),
  @JsonSubTypes.Type(value = Part.Refusal.class, name = "refusal"),
  @JsonSubTypes.Type(value = Part.Reasoning.class, name = "reasoning")
})
public sealed interface Part {
  record Text(String content) implements Part {}

  record Uri(String modality, String uri) implements Part {}

  record Blob(String modality, String mimeType, String content) implements Part {}

  /** {@code arguments} is the raw argument text; it is parsed once, when the span is derived. */
  record ToolCall(String id, String name, String arguments) implements Part {}

  record ToolResponse(String id, String response) implements Part {}

  record Refusal(String content) implements Part {}

  record Reasoning(String content) implements Part {}
}
