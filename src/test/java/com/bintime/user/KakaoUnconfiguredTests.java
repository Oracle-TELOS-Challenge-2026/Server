package com.bintime.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "bintime.auth.client-id=")
@ActiveProfiles("test")
class KakaoUnconfiguredTests {
  @LocalServerPort private int port;

  @Test
  void missingKeysDoNotPreventStartupOrExposeProtectedUserApis() throws Exception {
    assertThat(get("/swagger-ui/index.html").statusCode()).isEqualTo(200);
    assertThat(get("/api/v1/auth/oauth/kakao/start").statusCode()).isEqualTo(503);
    assertThat(get("/api/v1/auth/csrf").statusCode()).isEqualTo(401);
    assertThat(get("/api/v1/users/me").statusCode()).isEqualTo(401);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
  }
}
