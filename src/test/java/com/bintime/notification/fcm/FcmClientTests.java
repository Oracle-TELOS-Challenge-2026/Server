package com.bintime.notification.fcm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.bintime.notification.dto.NotificationResponse;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class FcmClientTests {
  private final JsonMapper json = JsonMapper.builder().build();
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC);
  private final AtomicInteger requests = new AtomicInteger();
  private HttpServer server;
  private int status;
  private String responseBody;
  private String retryAfter;
  private String authorization;
  private String requestPath;
  private JsonNode requestBody;
  private long responseDelayMillis;

  @BeforeEach
  void startServer() throws IOException {
    status = 200;
    responseBody = "{\"name\":\"projects/bintime-33512/messages/accepted-1\"}";
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          authorization = exchange.getRequestHeaders().getFirst("Authorization");
          requestPath = exchange.getRequestURI().getPath();
          requestBody = json.readTree(exchange.getRequestBody().readAllBytes());
          try {
            Thread.sleep(responseDelayMillis);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exchange.close();
            return;
          }
          if (retryAfter != null) exchange.getResponseHeaders().set("Retry-After", retryAfter);
          byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void sendsFidWithStringDataAndFrontendHttpsClickLink() {
    var result = client("https://frontend-oracle.vercel.app").send("browser-fid", notification());

    assertThat(result.accepted()).isTrue();
    assertThat(result.retryable()).isFalse();
    assertThat(result.code()).isEqualTo("ACCEPTED");
    assertThat(authorization).isEqualTo("Bearer fake-access-token");
    assertThat(requestPath).isEqualTo("/v1/projects/bintime-33512/messages:send");
    JsonNode message = requestBody.path("message");
    assertThat(message.path("fid").asText()).isEqualTo("browser-fid");
    assertThat(message.has("token")).isFalse();
    assertThat(message.path("notification").path("title").asText()).isEqualTo("빈자리 알림");
    assertThat(message.path("notification").path("body").asText()).isEqualTo("조건에 맞는 빈자리가 생겼어요.");
    assertThat(message.path("data").path("url").asText()).isEqualTo("/customer/waitlist");
    assertThat(message.path("data").path("notificationId").asText()).isEqualTo("noti-1");
    assertThat(message.path("webpush").path("fcm_options").path("link").asText())
        .isEqualTo("https://frontend-oracle.vercel.app/customer/waitlist");
  }

  @Test
  void localHttpOmitsHttpsOnlyFcmOptions() {
    assertThat(client("http://localhost:5173").send("browser-fid", notification()).accepted())
        .isTrue();
    assertThat(requestBody.path("message").has("webpush")).isFalse();
    assertThat(requestBody.path("message").path("data").path("url").asText())
        .isEqualTo("/customer/waitlist");
  }

  @Test
  void onlyExplicitFcmUnregisteredErrorIdentifiesAnInactiveInstallation() {
    status = 404;
    responseBody =
        "{\"error\":{\"status\":\"NOT_FOUND\",\"details\":[{\"@type\":"
            + "\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"UNREGISTERED\"}]}}";
    assertThat(client("http://localhost:5173").send("browser-fid", notification()))
        .isEqualTo(new FcmClient.Result(false, "UNREGISTERED", false, 0));

    responseBody = "{\"error\":{\"status\":\"NOT_FOUND\"}}";
    assertThat(client("http://localhost:5173").send("browser-fid", notification()).code())
        .isEqualTo("NOT_FOUND");
    status = 400;
    responseBody =
        "{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"message\":\"provider private detail\"}}";
    assertThat(client("http://localhost:5173").send("browser-fid", notification()))
        .isEqualTo(new FcmClient.Result(false, "INVALID_ARGUMENT", false, 0));
  }

  @Test
  void rateLimitsHonorRetryAfterAndNeverRetryImmediately() {
    status = 429;
    responseBody = "{\"error\":{\"status\":\"QUOTA_EXCEEDED\"}}";
    retryAfter = "120";
    var client = client("http://localhost:5173");
    assertThat(client.send("browser-fid", notification()))
        .isEqualTo(new FcmClient.Result(false, "QUOTA_EXCEEDED", true, 120));
    retryAfter = "2";
    assertThat(client.send("browser-fid", notification()).retryAfterSeconds()).isEqualTo(60);
    assertThat(requests).hasValue(2);
  }

  @Test
  void serverErrorsHonorDateRetryAfterAndSanitizeMalformedResponses() {
    status = 503;
    responseBody = "upstream private diagnostic";
    retryAfter = "Fri, 16 Oct 2026 12:03:00 GMT";
    var client = client("http://localhost:5173");
    assertThat(client.send("browser-fid", notification()))
        .isEqualTo(new FcmClient.Result(false, "HTTP_503", true, 180));
    retryAfter = "invalid";
    assertThat(client.send("browser-fid", notification()).retryAfterSeconds()).isEqualTo(60);
  }

  @Test
  void refreshesCredentialsThroughGoogleAuthBeforeSending() {
    AtomicInteger refreshes = new AtomicInteger();
    GoogleCredentials credentials =
        new GoogleCredentials() {
          @Override
          public AccessToken refreshAccessToken() {
            refreshes.incrementAndGet();
            return new AccessToken(
                "refreshed-fake-token", new Date(System.currentTimeMillis() + 3_600_000));
          }
        };
    var client = client(credentials, "http://localhost:5173", Duration.ofSeconds(2));
    assertThat(client.send("browser-fid", notification()).accepted()).isTrue();
    assertThat(client.send("browser-fid", notification()).accepted()).isTrue();
    assertThat(refreshes).hasValue(1);
    assertThat(authorization).isEqualTo("Bearer refreshed-fake-token");
  }

  @Test
  void credentialFailureIsRetryableWithoutLeakingProviderDetailsOrSending() {
    GoogleCredentials credentials =
        new GoogleCredentials() {
          @Override
          public AccessToken refreshAccessToken() throws IOException {
            throw new IOException("sensitive-provider-details");
          }
        };
    assertThat(
            client(credentials, "http://localhost:5173", Duration.ofSeconds(2))
                .send("browser-fid", notification()))
        .isEqualTo(new FcmClient.Result(false, "AUTHENTICATION_FAILED", true, 60));
    assertThat(requests).hasValue(0);
  }

  @Test
  void sessionExpiringDuringTokenRefreshPreventsHttpSubmission() {
    AtomicBoolean active = new AtomicBoolean(true);
    GoogleCredentials credentials =
        new GoogleCredentials() {
          @Override
          public AccessToken refreshAccessToken() {
            active.set(false);
            return new AccessToken(
                "refreshed-fake-token", new Date(System.currentTimeMillis() + 3_600_000));
          }
        };
    var client = client(credentials, "http://localhost:5173", Duration.ofSeconds(2));
    assertThat(client.send("browser-fid", notification(), active::get))
        .isEqualTo(new FcmClient.Result(false, "INACTIVE_SUBSCRIPTION", false, 0));
    assertThat(active).isFalse();
    assertThat(requests).hasValue(0);
  }

  @Test
  void disabledClientDoesNotReadCredentialFilesOrSubmitMessages() {
    var client =
        new FcmClient(json, false, "bintime-33512", "missing-secret.json", "http://localhost:5173");
    assertThat(client.enabled()).isFalse();
    assertThat(client.send("browser-fid", notification()))
        .isEqualTo(new FcmClient.Result(false, "DISABLED", false, 0));
    assertThat(requests).hasValue(0);
  }

  @Test
  void rejectsInvalidTargetsAndPayloadBeforeContactingProvider() {
    var client = client("http://localhost:5173");
    for (String fid :
        new String[] {"", "contains space", "line\nbreak", "space\u00a0", "x".repeat(129)}) {
      assertThat(client.send(fid, notification()).code()).isEqualTo("INVALID_INSTALLATION_ID");
    }
    assertThat(client.send(null, notification()).code()).isEqualTo("INVALID_INSTALLATION_ID");
    assertThat(client.send("browser-fid", null).code()).isEqualTo("INVALID_ARGUMENT");
    assertThat(requests).hasValue(0);
  }

  @Test
  void rejectsInjectedProjectOrNonOriginClickUrl() {
    for (String project : new String[] {"bintime-33512/other", "../project", "invalid project"})
      assertThatIllegalArgumentException().isThrownBy(() -> FcmClient.endpoint(project));
    for (String frontend :
        new String[] {
          "https://good.example/redirect",
          "https://good.example?next=bad",
          "https://user@good.example",
          "http://public.example",
          "javascript:alert(1)",
          "http:///missing-host"
        })
      assertThatIllegalArgumentException().isThrownBy(() -> FcmClient.frontendOrigin(frontend));
  }

  @Test
  void credentialConfigRejectsRelativePathsAndOtherCredentialTypes(@TempDir Path temp)
      throws IOException {
    assertThatIllegalStateException()
        .isThrownBy(() -> FcmClient.loadCredentials("bintime-33512", "secret.json"))
        .withMessageNotContaining("secret.json");
    Path wrongType = temp.resolve("wrong-type.json");
    Files.writeString(
        wrongType, "{\"type\":\"authorized_user\",\"client_secret\":\"private-content\"}");
    assertThatIllegalStateException()
        .isThrownBy(() -> FcmClient.loadCredentials("bintime-33512", wrongType.toString()))
        .withMessageNotContaining("private-content");
  }

  @Test
  void malformedSuccessDoesNotClaimAcceptedDelivery() {
    responseBody = "{}";
    assertThat(client("http://localhost:5173").send("browser-fid", notification()))
        .isEqualTo(new FcmClient.Result(false, "INVALID_RESPONSE", true, 60));
  }

  @Test
  void serviceAccountProjectMustMatchConfiguredProject(@TempDir Path temp) throws Exception {
    var key = KeyPairGenerator.getInstance("RSA").generateKeyPair().getPrivate();
    String pem =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(key.getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    Path fakeCredential = temp.resolve("test-service-account.json");
    Files.writeString(
        fakeCredential,
        json.writeValueAsString(
            Map.of(
                "type",
                "service_account",
                "project_id",
                "other-project",
                "client_id",
                "test-client",
                "client_email",
                "test@other-project.iam.gserviceaccount.com",
                "private_key_id",
                "test-key-id",
                "private_key",
                pem,
                "token_uri",
                "https://oauth2.googleapis.com/token")));
    assertThatIllegalStateException()
        .isThrownBy(() -> FcmClient.loadCredentials("bintime-33512", fakeCredential.toString()))
        .withMessageNotContaining("other-project");
    assertThat(requests).hasValue(0);
  }

  @Test
  void requestTimeoutIsRetryableAndDoesNotClaimDelivery() {
    responseDelayMillis = 250;
    var credentials =
        GoogleCredentials.create(
            new AccessToken("fake-access-token", new Date(System.currentTimeMillis() + 3_600_000)));
    var client = client(credentials, "http://localhost:5173", Duration.ofMillis(50));
    assertThat(client.send("browser-fid", notification()))
        .isEqualTo(new FcmClient.Result(false, "CONNECTION_FAILED", true, 60));
  }

  private FcmClient client(String frontend) {
    return client(
        GoogleCredentials.create(
            new AccessToken("fake-access-token", new Date(System.currentTimeMillis() + 3_600_000))),
        frontend,
        Duration.ofSeconds(2));
  }

  private FcmClient client(GoogleCredentials credentials, String frontend, Duration timeout) {
    URI endpoint =
        URI.create(
            "http://127.0.0.1:"
                + server.getAddress().getPort()
                + "/v1/projects/bintime-33512/messages:send");
    return new FcmClient(
        json, credentials, HttpClient.newHttpClient(), endpoint, frontend, timeout, clock);
  }

  private NotificationResponse notification() {
    return new NotificationResponse(
        "noti-1",
        "VACANCY_MATCHED",
        "빈자리 알림",
        "조건에 맞는 빈자리가 생겼어요.",
        null,
        null,
        "AVAILABLE",
        null,
        OffsetDateTime.now(clock));
  }
}
