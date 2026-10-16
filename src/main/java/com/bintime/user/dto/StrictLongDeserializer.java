package com.bintime.user.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

/** 소수 금액·버전을 잘라서 저장하는 기본 강제 변환을 막는다. */
public class StrictLongDeserializer extends StdDeserializer<Long> {
  public StrictLongDeserializer() {
    super(Long.class);
  }

  @Override
  public Long deserialize(JsonParser parser, DeserializationContext context) {
    if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
      return context.reportInputMismatch(Long.class, "JSON 정수가 필요합니다.");
    }
    return parser.getLongValue();
  }
}
