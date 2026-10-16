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
import io.swagger.v3.oas.models.responses.ApiResponse;
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
                "Notion 명세 기반 계약 골격. 성공 응답은 향후 구현할 계약이며 Controller는 501을 반환합니다. 인증·검증·비즈니스 로직은"
                    + " 미구현입니다. C-WS는 HTTP operation이 아닌 ChatWebSocketFrame 스키마와"
                    + " docs/contracts/realtime.md를 참고하세요."))
@SecurityScheme(
    name = "sessionCookie",
    type = SecuritySchemeType.APIKEY,
    in = SecuritySchemeIn.COOKIE,
    paramName = "bt_session",
    description = "명세상 세션 쿠키. 로그인 로직 미구현")
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
            .forEach(api.getComponents()::addSchemas);
      }
      api.getPaths()
          .forEach(
              (path, item) ->
                  item.readOperations()
                      .forEach(
                          operation -> {
                            for (String code :
                                new String[] {
                                  "400", "401", "403", "404", "409", "422", "429", "500", "501",
                                  "503"
                                }) {
                              operation
                                  .getResponses()
                                  .addApiResponse(
                                      code,
                                      new ApiResponse()
                                          .description("공통 실패 응답 (resultType=FAILURE)")
                                          .content(
                                              new Content()
                                                  .addMediaType(
                                                      "application/json",
                                                      new MediaType()
                                                          .schema(
                                                              new Schema<>()
                                                                  .$ref(
                                                                      "#/components/schemas/ApiErrorResponse")))));
                            }
                            if (path.endsWith("/notifications/stream")) {
                              operation
                                  .getResponses()
                                  .addApiResponse(
                                      "410",
                                      new ApiResponse()
                                          .description("STREAM_CURSOR_EXPIRED: 새 연결 후 알림함 재조회")
                                          .content(
                                              new Content()
                                                  .addMediaType(
                                                      "application/json",
                                                      new MediaType()
                                                          .schema(
                                                              new Schema<>()
                                                                  .$ref(
                                                                      "#/components/schemas/ApiErrorResponse")))));
                            }
                          }));
    };
  }
}
