package com.bintime.user.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

/** 선호도 text의 생략과 null을 구분하고 숫자·불리언의 문자열 강제 변환을 막는다. */
public class StrictStringDeserializer extends StdDeserializer<String> {
  public StrictStringDeserializer() {
    super(String.class);
  }

  @Override
  public String deserialize(JsonParser parser, DeserializationContext context) {
    if (!parser.hasToken(JsonToken.VALUE_STRING)) {
      return context.reportInputMismatch(String.class, "JSON 문자열이 필요합니다.");
    }
    return parser.getString();
  }

  @Override
  public String getNullValue(DeserializationContext context) {
    return context.reportInputMismatch(String.class, "null 대신 빈 문자열로 초기화해 주세요.");
  }

  @Override
  public String getAbsentValue(DeserializationContext context) {
    return null;
  }
}
