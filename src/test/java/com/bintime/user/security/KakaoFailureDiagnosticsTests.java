package com.bintime.user.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.json.JsonMapper;

class KakaoFailureDiagnosticsTests {
  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void nestedProviderFailureExposesOnlyStatusAndAllowedKakaoCode() {
    var response =
        response(
            """
            {"error_code":"KOE237","error_description":"code=private-code","access_token":"private-token"}
            """);
    var failure = new RuntimeException("private-message", new RuntimeException(response));
    assertThat(KakaoSecurityConfig.providerFailure(failure, mapper))
        .isEqualTo("http=429, provider=KOE237");
  }

  @Test
  void malformedBodiesAndUntrustedProviderCodesAreNotLogged() {
    for (String body :
        new String[] {
          "private-response", "null", "{}", "{\"error_code\":\"KOE237 private-code\"}"
        }) {
      assertThat(KakaoSecurityConfig.providerFailure(response(body), mapper))
          .isEqualTo("http=429, provider=unknown");
    }
  }

  @Test
  void nonHttpFailureIncludesTypeWithoutExceptionMessage() {
    assertThat(
            KakaoSecurityConfig.providerFailure(
                new IllegalStateException("private-message"), mapper))
        .isEqualTo("cause=IllegalStateException");
  }

  private static HttpClientErrorException response(String body) {
    return HttpClientErrorException.create(
        HttpStatus.TOO_MANY_REQUESTS,
        "private-status",
        HttpHeaders.EMPTY,
        body.getBytes(StandardCharsets.UTF_8),
        StandardCharsets.UTF_8);
  }
}
