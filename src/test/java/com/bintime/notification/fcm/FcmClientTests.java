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
import java.util.HashMap;
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
  void sendsChatStringDataWithoutChangingMatchingNotificationShape() {
    Map<String, String> data =
        Map.of(
            "type",
            "CHAT_MESSAGE",
            "roomId",
            "room-1",
            "messageId",
            "message-1",
            "sequence",
            "12",
            "url",
            "https://untrusted.example");
    var result =
        client("https://frontend-oracle.vercel.app")
            .sendPush("browser-fid", "새 채팅 메시지", "예약 문의입니다", data, "/", () -> true);

    assertThat(result.accepted()).isTrue();
    JsonNode message = requestBody.path("message");
    assertThat(message.path("fid").asText()).isEqualTo("browser-fid");
    assertThat(message.has("token")).isFalse();
    assertThat(message.path("notification").path("title").asText()).isEqualTo("새 채팅 메시지");
    assertThat(message.path("notification").path("body").asText()).isEqualTo("예약 문의입니다");
    assertThat(message.path("data").path("type").asText()).isEqualTo("CHAT_MESSAGE");
    assertThat(message.path("data").path("roomId").asText()).isEqualTo("room-1");
    assertThat(message.path("data").path("messageId").asText()).isEqualTo("message-1");
    assertThat(message.path("data").path("sequence").asText()).isEqualTo("12");
    assertThat(message.path("data").has("notificationId")).isFalse();
    assertThat(message.path("data").path("url").asText()).isEqualTo("/");
    for (JsonNode value : message.path("data")) assertThat(value.isString()).isTrue();
    assertThat(message.path("webpush").path("fcm_options").path("link").asText())
        .isEqualTo("https://frontend-oracle.vercel.app/");
    assertThat(data).containsEntry("url", "https://untrusted.example");
  }

  @Test
  void customRelativePathStaysOnFrontendOriginAndLocalHttpOmitsClickOptions() {
    var client = client("https://frontend-oracle.vercel.app/");
    assertThat(
            client
                .sendPush("browser-fid", "채팅", "새 메시지", Map.of(), "/?roomId=room-1", () -> true)
                .accepted())
        .isTrue();
    assertThat(requestBody.at("/message/webpush/fcm_options/link").asText())
        .isEqualTo("https://frontend-oracle.vercel.app/?roomId=room-1");
    assertThat(
            client("http://localhost:5173")
                .sendPush(
                    "browser-fid", "채팅", "새 메시지", Map.of("type", "CHAT_MESSAGE"), "/", () -> true)
                .accepted())
        .isTrue();
    assertThat(requestBody.path("message").has("webpush")).isFalse();
    assertThat(requestBody.at("/message/data/url").asText()).isEqualTo("/");
  }

  @Test
  void customClickPathsCannotChangeOriginOrInjectControls() {
    var client = client("https://frontend-oracle.vercel.app");
    for (String path :
        new String[] {
          null,
          "",
          "relative",
          "https://untrusted.example/",
          "//untrusted.example",
          "///untrusted.example",
          "/\\untrusted.example",
          "/line\nbreak",
          "/tab\t",
          "/carriage\rreturn",
          "/bad path",
          "/%invalid"
        }) {
      assertThat(client.sendPush("browser-fid", "채팅", "새 메시지", Map.of(), path, () -> true))
          .isEqualTo(new FcmClient.Result(false, "INVALID_ARGUMENT", false, 0));
    }
    assertThat(requests).hasValue(0);
  }

  @Test
  void customPushCountsUtf8BytesAcrossNotificationAndDataBeforeHttp() {
    var client = client("https://frontend-oracle.vercel.app");
    assertThat(
            client
                .sendPush("browser-fid", "채팅", "한".repeat(1400), Map.of(), "/", () -> true)
                .code())
        .isEqualTo("INVALID_ARGUMENT");
    assertThat(
            client
                .sendPush(
                    "browser-fid",
                    "채팅",
                    "새 메시지",
                    Map.of("preview", "한".repeat(1400)),
                    "/",
                    () -> true)
                .code())
        .isEqualTo("INVALID_ARGUMENT");
    assertThat(requests).hasValue(0);
    assertThat(
            client
                .sendPush("browser-fid", "채팅", "한".repeat(100), Map.of(), "/", () -> true)
                .accepted())
        .isTrue();
  }

  @Test
  void customPushRejectsInvalidTargetMissingTextAndNonStringNullData() {
    var client = client("http://localhost:5173");
    assertThat(client.sendPush("x".repeat(129), "title", "body", Map.of(), "/", () -> true).code())
        .isEqualTo("INVALID_INSTALLATION_ID");
    assertThat(client.sendPush("browser-fid", null, "body", Map.of(), "/", () -> true).code())
        .isEqualTo("INVALID_ARGUMENT");
    assertThat(client.sendPush("browser-fid", "title", null, Map.of(), "/", () -> true).code())
        .isEqualTo("INVALID_ARGUMENT");
    assertThat(client.sendPush("browser-fid", "title", "body", null, "/", () -> true).code())
        .isEqualTo("INVALID_ARGUMENT");
    assertThat(client.sendPush("browser-fid", "title", "body", Map.of(), "/", null).code())
        .isEqualTo("INVALID_ARGUMENT");
    var data = new HashMap<String, String>();
    data.put("roomId", null);
    assertThat(client.sendPush("browser-fid", "title", "body", data, "/", () -> true).code())
        .isEqualTo("INVALID_ARGUMENT");
    data.clear();
    data.put(null, "room-1");
    assertThat(client.sendPush("browser-fid", "title", "body", data, "/", () -> true).code())
        .isEqualTo("INVALID_ARGUMENT");
    assertThat(requests).hasValue(0);
  }

  @Test
  void customPushRechecksSessionAfterOAuthRefresh() {
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
    assertThat(
            client(credentials, "http://localhost:5173", Duration.ofSeconds(2))
                .sendPush(
                    "browser-fid", "채팅", "새 메시지", Map.of("type", "CHAT_MESSAGE"), "/", active::get))
        .isEqualTo(new FcmClient.Result(false, "INACTIVE_SUBSCRIPTION", false, 0));
    assertThat(requests).hasValue(0);
  }

  @Test
  void customPushUsesExistingRetryAfterAndErrorHandling() {
    status = 429;
    retryAfter = "120";
    responseBody = "{\"error\":{\"status\":\"QUOTA_EXCEEDED\"}}";
    assertThat(
            client("http://localhost:5173")
                .sendPush("browser-fid", "채팅", "새 메시지", Map.of(), "/", () -> true))
        .isEqualTo(new FcmClient.Result(false, "QUOTA_EXCEEDED", true, 120));
  }

  @Test
  void matchingNotificationStillRejectsMissingOrBlankId() {
    var client = client("http://localhost:5173");
    for (String id : new String[] {null, "", " "}) {
      var notification =
          new NotificationResponse(
              id,
              "VACANCY_MATCHED",
              "title",
              "body",
              null,
              null,
              "AVAILABLE",
              null,
              OffsetDateTime.now(clock));
      assertThat(client.send("browser-fid", notification).code()).isEqualTo("INVALID_ARGUMENT");
    }
    assertThat(requests).hasValue(0);
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
    assertThat(client.sendPush("browser-fid", "채팅", "메시지", Map.of(), "/", () -> true))
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
