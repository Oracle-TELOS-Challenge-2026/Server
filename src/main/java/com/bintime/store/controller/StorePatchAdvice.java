package com.bintime.store.controller;

import com.bintime.common.web.ApiBusinessException;
import com.bintime.store.dto.UpdateStoreRequest;
import com.bintime.store.dto.UpdateStoreServiceRequest;
import java.io.*;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.databind.json.JsonMapper;

/** PATCH에서 생략은 유지, 명시적 null은 오류다. record의 null만으로는 둘을 구분할 수 없다. */
@ControllerAdvice(assignableTypes = OwnerStoreController.class)
public class StorePatchAdvice extends RequestBodyAdviceAdapter {
  private final JsonMapper json;

  public StorePatchAdvice(JsonMapper json) {
    this.json = json;
  }

  @Override
  public boolean supports(
      MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
    return type == UpdateStoreRequest.class || type == UpdateStoreServiceRequest.class;
  }

  @Override
  public HttpInputMessage beforeBodyRead(
      HttpInputMessage input,
      MethodParameter parameter,
      Type type,
      Class<? extends HttpMessageConverter<?>> converter)
      throws IOException {
    byte[] bytes = input.getBody().readAllBytes();
    tools.jackson.databind.JsonNode node;
    try {
      node = json.readTree(bytes);
    } catch (tools.jackson.core.JacksonException ex) {
      throw new org.springframework.http.converter.HttpMessageNotReadableException(
          "Malformed JSON", ex, input);
    }
    if (node != null && node.isObject())
      for (var field : node.properties())
        if (field.getValue().isNull()) throw new ApiBusinessException(400, "NULL_PATCH_FIELD");
    return new HttpInputMessage() {
      public HttpHeaders getHeaders() {
        return input.getHeaders();
      }

      public InputStream getBody() {
        return new ByteArrayInputStream(bytes);
      }
    };
  }
}
