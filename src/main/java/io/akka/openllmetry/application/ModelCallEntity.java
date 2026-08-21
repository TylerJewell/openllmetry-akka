package io.akka.openllmetry.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.openllmetry.domain.CallEvent;
import io.akka.openllmetry.domain.CallState;
import io.akka.openllmetry.domain.Chunk;
import io.akka.openllmetry.domain.Message;
import io.akka.openllmetry.domain.Request;
import io.akka.openllmetry.domain.Span;
import io.akka.openllmetry.domain.Usage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One model call, from the request that opened it to the span it becomes. SPEC-001 rules 11, 18
 * and 19.
 *
 * <p>One entity per call, because everything the span depends on is a fact about that one call and
 * about no other: the deltas belong to it in the order they arrived, and no other call's deltas can
 * change what it says.
 *
 * <p>The deltas are persisted rather than held in memory, which is the whole of what this port adds
 * — a stream interrupted halfway resumes from the record instead of losing what had accumulated.
 */
@Component(id = "model-call")
public class ModelCallEntity extends EventSourcedEntity<CallState, CallEvent> {

  public record Open(Request request, List<Message> inputMessages) {}

  public record Chunks(
      String deliveryId,
      String responseId,
      String responseModel,
      String systemFingerprint,
      List<Chunk> chunks,
      Usage usage) {}

  public record Failed(String type, String message) {}

  /** One recorded delta, numbered, so a reader that reconnects can say where it got to. */
  public record Progress(long sequence, String text) {}

  @Override
  public CallState emptyState() {
    return CallState.empty();
  }

  public Effect<Done> open(Open command) {
    if (currentState().request() != null) {
      return effects().error("call " + commandContext().entityId() + " is already open");
    }
    return effects()
        .persist(new CallEvent.Opened(
            commandContext().entityId(), command.request(), command.inputMessages()))
        .thenReply(state -> Done.getInstance());
  }

  public Effect<Done> chunk(Chunks command) {
    if (currentState().request() == null) {
      return effects().error("call " + commandContext().entityId() + " has not been opened");
    }
    if (!currentState().isOpen()) {
      return effects().error("call " + commandContext().entityId() + " is no longer accepting chunks");
    }
    // A redelivered batch is accepted and ignored: content is appended, so persisting it twice
    // would silently double the answer rather than fail.
    if (currentState().hasSeen(command.deliveryId())) {
      return effects().reply(Done.getInstance());
    }
    return effects()
        .persist(new CallEvent.ChunksReceived(
            command.deliveryId(),
            command.responseId(),
            command.responseModel(),
            command.systemFingerprint(),
            command.chunks(),
            command.usage()))
        .thenReply(state -> Done.getInstance());
  }

  public Effect<Done> close() {
    if (!currentState().isOpen()) {
      return effects().reply(Done.getInstance());
    }
    return effects().persist(new CallEvent.Closed()).thenReply(state -> Done.getInstance());
  }

  public Effect<Done> fail(Failed command) {
    if (!currentState().isOpen()) {
      return effects().reply(Done.getInstance());
    }
    return effects()
        .persist(new CallEvent.Failed(command.type(), command.message()))
        .thenReply(state -> Done.getInstance());
  }

  public Effect<Done> suppress() {
    if (currentState().suppressed()) {
      return effects().reply(Done.getInstance());
    }
    return effects().persist(new CallEvent.Suppressed()).thenReply(state -> Done.getInstance());
  }

  public ReadOnlyEffect<Optional<Span>> span() {
    return effects().reply(Span.of(currentState(), ContentPolicy.traceContent()));
  }

  public ReadOnlyEffect<List<Progress>> progress() {
    var out = new ArrayList<Progress>();
    var log = currentState().contentLog();
    long first = currentState().firstRetainedDelta();
    for (int i = 0; i < log.size(); i++) {
      out.add(new Progress(first + i, log.get(i)));
    }
    return effects().reply(List.copyOf(out));
  }

  @Override
  public CallState applyEvent(CallEvent event) {
    return currentState().apply(event);
  }
}
