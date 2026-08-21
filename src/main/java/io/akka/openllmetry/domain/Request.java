package io.akka.openllmetry.domain;

/** What was asked of the model. Every optional field is null when it was not supplied. */
public record Request(
    String model,
    String baseUrl,
    Double temperature,
    Double topP,
    Long maxTokens,
    Double frequencyPenalty,
    Double presencePenalty,
    String user,
    boolean streaming) {}
