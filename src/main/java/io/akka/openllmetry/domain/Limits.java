package io.akka.openllmetry.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * What one call may hold. SPEC-001 decision D3.
 *
 * <p>An entity's state and each of its events must stay inside the runtime's replication ceiling,
 * and a prompt carrying an inline image is the one input that can reach it in a single message. The
 * cap is applied where the content is recorded, so what is held is bounded by construction rather
 * than by hoping the caller was reasonable.
 */
public final class Limits {

  /** The most content kept for one text or inline-blob part, and for one accumulated answer. */
  public static final int CONTENT_CAP = 128 * 1024;

  /** The most per-delta progress entries kept for a reader catching up. */
  public static final int PROGRESS_ENTRIES = 1000;

  /** The most delivery identifiers remembered for de-duplication. */
  public static final int DELIVERIES_REMEMBERED = 1000;

  public static String cap(String text) {
    if (text == null || text.length() <= CONTENT_CAP) return text;
    return text.substring(0, CONTENT_CAP);
  }

  /** Trims every part that carries free text, leaving the message and part structure intact. */
  public static List<Message> capped(List<Message> messages) {
    var out = new ArrayList<Message>(messages.size());
    for (var m : messages) {
      var parts = new ArrayList<Part>(m.parts().size());
      for (var p : m.parts()) {
        parts.add(
            switch (p) {
              case Part.Text t -> new Part.Text(cap(t.content()));
              case Part.Blob b -> new Part.Blob(b.modality(), b.mimeType(), cap(b.content()));
              case Part.Refusal r -> new Part.Refusal(cap(r.content()));
              case Part.Reasoning r -> new Part.Reasoning(cap(r.content()));
              case Part.ToolCall t -> new Part.ToolCall(t.id(), t.name(), cap(t.arguments()));
              case Part.ToolResponse t -> new Part.ToolResponse(t.id(), cap(t.response()));
              case Part.Uri u -> u;
            });
      }
      out.add(new Message(m.role(), List.copyOf(parts)));
    }
    return List.copyOf(out);
  }

  /** Keeps the last {@code max} of a growing list, so replaying a long call cannot outgrow state. */
  public static <T> List<T> lastOf(List<T> values, int max) {
    if (values.size() <= max) return List.copyOf(values);
    return List.copyOf(values.subList(values.size() - max, values.size()));
  }

  private Limits() {}
}
