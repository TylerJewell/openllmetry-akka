package io.akka.openllmetry.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * SPEC-001 rules 12 and 13, and decision D3 — folding streamed deltas into one answer per choice.
 *
 * <p>The fold is expressed over a starting set of choices rather than over a whole stream, because
 * the entity persists deltas as they arrive: what is replayed from the journal is a sequence of
 * small batches, and the result must not depend on how the deltas were grouped into them.
 */
public final class Accumulator {

  /** SPEC-001 decision D3, shared with everything else one call may hold. */
  public static final int CONTENT_CAP = Limits.CONTENT_CAP;

  public static List<Choice> apply(List<Choice> existing, List<Chunk> chunks) {
    var byIndex = new TreeMap<Integer, Draft>();
    for (var c : existing) {
      byIndex.put(c.index(), Draft.from(c));
    }
    for (var chunk : chunks) {
      if (chunk.carriesNothing() && !byIndex.containsKey(chunk.index())) continue;
      byIndex.computeIfAbsent(chunk.index(), Draft::new).absorb(chunk);
    }
    return byIndex.values().stream().map(Draft::toChoice).toList();
  }

  private static final class Draft {
    private final int index;
    private String role;
    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private String refusal;
    private String finishReason;
    private boolean truncated;
    private final List<CallDraft> calls = new ArrayList<>();

    Draft(int index) {
      this.index = index;
    }

    static Draft from(Choice c) {
      var d = new Draft(c.index());
      d.role = c.role();
      if (c.content() != null) d.content.append(c.content());
      if (c.reasoning() != null) d.reasoning.append(c.reasoning());
      d.refusal = c.refusal();
      d.finishReason = c.finishReason();
      d.truncated = c.truncated();
      for (var tc : c.toolCalls()) {
        var cd = new CallDraft();
        cd.id = tc.id();
        cd.name = tc.name();
        cd.arguments.append(tc.arguments() == null ? "" : tc.arguments());
        d.calls.add(cd);
      }
      return d;
    }

    void absorb(Chunk chunk) {
      if (chunk.role() != null && !chunk.role().isEmpty()) role = chunk.role();
      if (chunk.content() != null) truncated |= append(content, chunk.content());
      if (chunk.reasoning() != null) truncated |= append(reasoning, chunk.reasoning());
      if (chunk.refusal() != null) refusal = chunk.refusal();
      if (chunk.finishReason() != null) finishReason = FinishReason.rewrite(chunk.finishReason());
      for (var delta : chunk.toolCalls()) {
        while (calls.size() <= delta.index()) {
          calls.add(new CallDraft());
        }
        var call = calls.get(delta.index());
        if (delta.id() != null) call.id = delta.id();
        if (delta.name() != null) call.name = delta.name();
        if (delta.arguments() != null) truncated |= append(call.arguments, delta.arguments());
      }
    }

    /** Returns whether the cap bit off any of what was appended. */
    private static boolean append(StringBuilder target, String addition) {
      int room = CONTENT_CAP - target.length();
      if (room <= 0) return !addition.isEmpty();
      if (addition.length() <= room) {
        target.append(addition);
        return false;
      }
      target.append(addition, 0, room);
      return true;
    }

    Choice toChoice() {
      var toolCalls =
          calls.stream().map(c -> new ToolCall(c.id, c.name, c.arguments.toString())).toList();
      return new Choice(
          index,
          role,
          // Never null once a choice exists: a choice that streamed no content still carries an
          // empty answer, which is a text part rather than the absence of one.
          content.toString(),
          toolCalls,
          refusal,
          reasoning.isEmpty() ? null : reasoning.toString(),
          finishReason,
          truncated);
    }
  }

  private static final class CallDraft {
    private String id;
    private String name;
    private final StringBuilder arguments = new StringBuilder();
  }

  private Accumulator() {}
}
