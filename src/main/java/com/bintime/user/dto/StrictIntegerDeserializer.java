package com.bintime.user.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

/** 위치 정확도를 소수에서 정수로 자동 변환하지 않는다. */
public class StrictIntegerDeserializer extends StdDeserializer<Integer> {
  public StrictIntegerDeserializer() {
    super(Integer.class);
  }

  @Override
  public Integer deserialize(JsonParser parser, DeserializationContext context) {
    if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
      return context.reportInputMismatch(Integer.class, "JSON 정수가 필요합니다.");
    }
    return parser.getIntValue();
  }
}
