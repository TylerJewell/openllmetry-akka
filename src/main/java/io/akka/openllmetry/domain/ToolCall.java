package io.akka.openllmetry.domain;

/** A tool call the model asked for. {@code arguments} is raw text until the span is derived. */
public record ToolCall(String id, String name, String arguments) {}
