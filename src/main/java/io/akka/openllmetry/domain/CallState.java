package io.akka.openllmetry.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything recorded about one model call. SPEC-001 §2.
 *
 * <p>Folded from the journal and never mutated, so that a call interrupted between chunks and one
 * that ran straight through arrive at the same state and therefore at the same span.
 */
public record CallState(
    String callId,
    Request request,
    List<Message> inputMessages,
    List<Choice> choices,
    Usage usage,
    String responseId,
    String responseModel,
    String systemFingerprint,
    Outcome outcome,
    Failure failure,
    boolean suppressed,
    List<String> contentLog,
    long deltasRecorded,
    long chunksRecorded,
    List<String> deliveries) {

  public enum Outcome {
    OPEN,
    OK,
    ERROR
  }

  public record Failure(String type, String message) {}

  public static CallState empty() {
    return new CallState(null, null, List.of(), List.of(), null, null, null, null,
        Outcome.OPEN, null, false, List.of(), 0L, 0L, List.of());
  }

  public static CallState opened(String callId, Request request) {
    return empty().apply(new CallEvent.Opened(callId, request, List.of()));
  }

  public boolean isOpen() {
    return outcome == Outcome.OPEN;
  }

  /** SPEC-001 rule 20 — a redelivered batch must not append its content a second time. */
  public boolean hasSeen(String deliveryId) {
    return deliveryId != null && deliveries.contains(deliveryId);
  }

  /** How many deltas were recorded in total, including any the progress list has since dropped. */
  public long firstRetainedDelta() {
    return deltasRecorded - contentLog.size();
  }

  public CallState withInputMessages(List<Message> messages) {
    return new CallState(callId, request, Limits.capped(messages), choices, usage, responseId,
        responseModel, systemFingerprint, outcome, failure, suppressed, contentLog, deltasRecorded,
        chunksRecorded, deliveries);
  }

  public CallState completed(
      String responseId, String responseModel, String fingerprint, List<Choice> choices,
      Usage usage) {
    return new CallState(callId, request, inputMessages, List.copyOf(choices), usage, responseId,
        responseModel, fingerprint, Outcome.OK, null, suppressed, contentLog, deltasRecorded,
        chunksRecorded, deliveries);
  }

  public CallState failed(String type, String message) {
    return new CallState(callId, request, inputMessages, choices, usage, responseId, responseModel,
        systemFingerprint, Outcome.ERROR, new Failure(type, message), suppressed, contentLog,
        deltasRecorded, chunksRecorded, deliveries);
  }

  public CallState markSuppressed() {
    return new CallState(callId, request, inputMessages, choices, usage, responseId, responseModel,
        systemFingerprint, outcome, failure, true, contentLog, deltasRecorded, chunksRecorded,
        deliveries);
  }

  public CallState apply(CallEvent event) {
    return switch (event) {
      case CallEvent.Opened e ->
          new CallState(e.callId(), e.request(), Limits.capped(e.inputMessages()), List.of(), null,
              null, null, null, Outcome.OPEN, null, suppressed, List.of(), 0L, 0L, List.of());
      case CallEvent.ChunksReceived e -> {
        var log = new ArrayList<>(contentLog);
        long recorded = deltasRecorded;
        for (var chunk : e.chunks()) {
          if (chunk.content() != null) {
            log.add(Limits.cap(chunk.content()));
            recorded++;
          }
        }
        var seen = new ArrayList<>(deliveries);
        if (e.deliveryId() != null) seen.add(e.deliveryId());
        yield new CallState(
            callId,
            request,
            inputMessages,
            Accumulator.apply(choices, e.chunks()),
            e.usage() != null ? e.usage() : usage,
            e.responseId() != null ? e.responseId() : responseId,
            e.responseModel() != null ? e.responseModel() : responseModel,
            e.systemFingerprint() != null ? e.systemFingerprint() : systemFingerprint,
            outcome,
            failure,
            suppressed,
            Limits.lastOf(log, Limits.PROGRESS_ENTRIES),
            recorded,
            chunksRecorded + e.chunks().size(),
            Limits.lastOf(seen, Limits.DELIVERIES_REMEMBERED));
      }
      case CallEvent.Closed ignored -> completed(responseId, responseModel, systemFingerprint,
          choices, usage);
      case CallEvent.Failed e -> failed(e.type(), e.message());
      case CallEvent.Suppressed ignored -> markSuppressed();
    };
  }
}
