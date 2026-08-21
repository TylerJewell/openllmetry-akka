package io.akka.openllmetry.domain;

import akka.javasdk.annotations.TypeName;
import java.util.List;

/** Everything that can happen to one model call. */
public sealed interface CallEvent {

  @TypeName("opened")
  record Opened(String callId, Request request, List<Message> inputMessages) implements CallEvent {}

  @TypeName("chunks-received")
  record ChunksReceived(
      String deliveryId,
      String responseId,
      String responseModel,
      String systemFingerprint,
      List<Chunk> chunks,
      Usage usage)
      implements CallEvent {}

  @TypeName("closed")
  record Closed() implements CallEvent {}

  @TypeName("failed")
  record Failed(String type, String message) implements CallEvent {}

  @TypeName("suppressed")
  record Suppressed() implements CallEvent {}
}
