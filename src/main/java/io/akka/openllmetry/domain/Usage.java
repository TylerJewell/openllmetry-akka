package io.akka.openllmetry.domain;

/** Token counts. {@code cacheReadInputTokens} is null when the response carried no cached detail. */
public record Usage(Long inputTokens, Long outputTokens, Long totalTokens, Long cacheReadInputTokens) {}
