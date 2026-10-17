package com.bintime.common.config;

import com.bintime.chatting.dto.ChatWebSocketFrame;
import com.bintime.common.dto.ApiErrorResponse;
import com.bintime.notification.dto.NotificationStreamPayload;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
    info =
        @Info(
            title = "BinTime API",
            version = "v0.1-contract",
            description =
                "user U-01~11, store ST-01~20, search S-01~08, notification N-01~06, chatting"
                    + " C-01~08은 구현된 API입니다. store·search는 세션 쿠키와 설정된 Bearer JWT 인증을 지원합니다. search"
                    + " 미연결 외부 의존 기능은 503, 알림은 세션에 연결한 Firebase Installation ID를 사용합니다. C-WS는 HTTP"
                    + " operation이 아닌 ChatWebSocketFrame 스키마와 docs/contracts/realtime.md를 참고하세요."))
@SecurityScheme(
    name = "sessionCookie",
    type = SecuritySchemeType.APIKEY,
    in = SecuritySchemeIn.COOKIE,
    paramName = "bt_session",
    description = "카카오 로그인 후 발급하는 HttpOnly 세션 쿠키")
@SecurityScheme(
    name = "bearerJwt",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
    description = "설정된 발급자의 RS256 JWT. sub는 내부 사용자 ID입니다. store·search API에서 지원합니다.")
public class OpenApiConfig {

  @Bean
  GroupedOpenApi allApi() {
    return GroupedOpenApi.builder()
        .group("all")
        .pathsToMatch("/api/v1/**")
        .addOpenApiCustomizer(contractSchemas())
        .build();
  }

  @Bean
  GroupedOpenApi publicApi() {
    return GroupedOpenApi.builder()
        .group("public")
        .pathsToMatch("/api/v1/**")
        .pathsToExclude("/api/v1/internal/**")
        .addOpenApiCustomizer(contractSchemas())
        .build();
  }

  @Bean
  GroupedOpenApi internalApi() {
    return GroupedOpenApi.builder()
        .group("internal")
        .pathsToMatch("/api/v1/internal/**")
        .addOpenApiCustomizer(contractSchemas())
        .build();
  }

  @Bean
  OpenApiCustomizer contractSchemas() {
    return api -> {
      // SSE와 WebSocket은 일반 MVC 반환 타입만으로 프레임 스키마가 발견되지 않는다.
      for (Class<?> type :
          new Class<?>[] {
            ChatWebSocketFrame.class, NotificationStreamPayload.class, ApiErrorResponse.class
          }) {
        ModelConverters.getInstance()
            .resolveAsResolvedSchema(new AnnotatedType(type))
            .referencedSchemas
            // HTTP에서 발견한 nullable 등 필드 계약을 별도 프레임 탐색 결과로 덮어쓰지 않는다.
            .forEach((name, schema) -> api.getComponents().getSchemas().putIfAbsent(name, schema));
      }
      Set<Schema<?>> visited = Collections.newSetFromMap(new IdentityHashMap<>());
      api.getComponents()
          .getSchemas()
          .values()
          .forEach(schema -> nullableReferences(schema, visited));
      api.getPaths()
          .forEach(
              (path, item) ->
                  item.readOperations()
                      .forEach(
                          operation -> {
                            // 각 API에 명시한 오류만 공통 봉투로 문서화하고 성공 응답은 유지한다.
                            operation
                                .getResponses()
                                .forEach(
                                    (code, response) -> {
                                      if (code.matches("[45]\\d{2}")) {
                                        response.content(
                                            new Content()
                                                .addMediaType(
                                                    "application/json",
                                                    new MediaType()
                                                        .schema(
                                                            new Schema<>()
                                                                .$ref(
                                                                    "#/components/schemas/ApiErrorResponse"))));
                                      }
                                    });
                          }));
    };
  }

  private static void nullableReferences(Schema<?> schema, Set<Schema<?>> visited) {
    if (schema == null || !visited.add(schema)) return;
    if (schema.get$ref() != null
        && (Boolean.TRUE.equals(schema.getNullable())
            || (schema.getTypes() != null && schema.getTypes().contains("null"))
            || "null".equals(schema.getType()))) {
      // 3.1의 $ref와 type:null은 동시에 적용된다. 객체 또는 null은 별도 대안이어야 한다.
      String ref = schema.get$ref();
      schema.set$ref(null);
      schema.setType(null);
      schema.setTypes(null);
      schema.setNullable(null);
      Schema<?> nullSchema = new Schema<>();
      nullSchema.setTypes(Set.of("null"));
      schema.setOneOf(List.of(new Schema<>().$ref(ref), nullSchema));
    }
    if (schema.getProperties() != null)
      schema.getProperties().values().forEach(child -> nullableReferences(child, visited));
    nullableReferences(schema.getItems(), visited);
    if (schema.getAdditionalProperties() instanceof Schema<?> child)
      nullableReferences(child, visited);
    if (schema.getOneOf() != null)
      schema.getOneOf().forEach(child -> nullableReferences(child, visited));
    if (schema.getAnyOf() != null)
      schema.getAnyOf().forEach(child -> nullableReferences(child, visited));
    if (schema.getAllOf() != null)
      schema.getAllOf().forEach(child -> nullableReferences(child, visited));
  }
}
