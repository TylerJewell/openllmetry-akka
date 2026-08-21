package io.akka.openllmetry.domain;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * One value on a finished span.
 *
 * <p>A tagged union rather than {@code Object}: a bare {@code Map<String, Object>} loses the
 * distinction between a 64-bit and a 32-bit integer across the journal, so a token count of
 * 9007199254740993 comes back as something else. SPEC-001 §2.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "t")
@JsonSubTypes({
  @JsonSubTypes.Type(value = AttrValue.Str.class, name = "s"),
  @JsonSubTypes.Type(value = AttrValue.Num.class, name = "i"),
  @JsonSubTypes.Type(value = AttrValue.Dec.class, name = "d"),
  @JsonSubTypes.Type(value = AttrValue.Bool.class, name = "b"),
  @JsonSubTypes.Type(value = AttrValue.StrList.class, name = "sl")
})
public sealed interface AttrValue {
  record Str(String v) implements AttrValue {}

  record Num(long v) implements AttrValue {}

  record Dec(double v) implements AttrValue {}

  record Bool(boolean v) implements AttrValue {}

  record StrList(List<String> v) implements AttrValue {}
}
