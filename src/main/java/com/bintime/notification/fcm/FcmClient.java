package com.bintime.notification.fcm;

import com.bintime.notification.dto.NotificationResponse;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("!api-docs")
public class FcmClient {
  private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
  private static final String CLICK_PATH = "/customer/waitlist";
  private static final Set<String> ERROR_CODES =
      Set.of(
          "INVALID_ARGUMENT",
          "UNREGISTERED",
          "SENDER_ID_MISMATCH",
          "QUOTA_EXCEEDED",
          "UNAVAILABLE",
          "INTERNAL",
          "THIRD_PARTY_AUTH_ERROR",
          "UNSPECIFIED_ERROR",
          "UNAUTHENTICATED",
          "PERMISSION_DENIED",
          "NOT_FOUND");

  private final JsonMapper json;
  private final GoogleCredentials credentials;
  private final HttpClient http;
  private final URI endpoint;
  private final URI frontend;
  private final Duration timeout;
  private final Clock clock;

  @Autowired
  public FcmClient(
      JsonMapper json,
      @Value("${bintime.fcm.enabled:false}") boolean enabled,
      @Value("${bintime.fcm.project-id:bintime-33512}") String projectId,
      @Value("${bintime.fcm.credentials-path:}") String credentialsPath,
      @Value("${bintime.auth.frontend-url:http://localhost:5173}") String frontendUrl) {
    this(
        json,
        enabled ? loadCredentials(projectId, credentialsPath) : null,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
        endpoint(projectId),
        frontendUrl,
        Duration.ofSeconds(10),
        Clock.systemUTC());
  }

  FcmClient(
      JsonMapper json,
      GoogleCredentials credentials,
      HttpClient http,
      URI endpoint,
      String frontendUrl,
      Duration timeout,
      Clock clock) {
    this.json = json;
    this.credentials = credentials;
    this.http = http;
    this.endpoint = endpoint;
    this.frontend = frontendOrigin(frontendUrl);
    this.timeout = timeout;
    this.clock = clock;
  }

  public boolean enabled() {
    return credentials != null;
  }

  public Result send(String installationId, NotificationResponse notification) {
    return send(installationId, notification, () -> true);
  }

  public Result send(
      String installationId, NotificationResponse notification, BooleanSupplier stillActive) {
    if (!enabled()) return new Result(false, "DISABLED", false, 0);
    if (installationId == null
        || installationId.isEmpty()
        || installationId.length() > 128
        || installationId
            .codePoints()
            .anyMatch(
                c ->
                    Character.isWhitespace(c)
                        || Character.isSpaceChar(c)
                        || Character.isISOControl(c)))
      return new Result(false, "INVALID_INSTALLATION_ID", false, 0);
    if (notification == null
        || notification.id() == null
        || notification.id().isBlank()
        || notification.title() == null
        || notification.body() == null) return new Result(false, "INVALID_ARGUMENT", false, 0);

    Map<String, Object> message = new LinkedHashMap<>();
    message.put("fid", installationId);
    message.put("notification", Map.of("title", notification.title(), "body", notification.body()));
    message.put("data", Map.of("url", CLICK_PATH, "notificationId", notification.id()));
    // FCM only accepts HTTPS click links; local HTTP still receives the relative data URL.
    if ("https".equals(frontend.getScheme()))
      message.put(
          "webpush",
          Map.of("fcm_options", Map.of("link", frontend.resolve(CLICK_PATH).toString())));
    String body = json.writeValueAsString(Map.of("message", message));
    if (body.getBytes(StandardCharsets.UTF_8).length > 4096)
      return new Result(false, "INVALID_ARGUMENT", false, 0);

    String token;
    try {
      credentials.refreshIfExpired();
      token = credentials.getAccessToken().getTokenValue();
    } catch (IOException e) {
      // Provider exceptions may contain credentials or response bodies: expose only our stable
      // code.
      return new Result(false, "AUTHENTICATION_FAILED", true, 60);
    }
    try {
      var request =
          HttpRequest.newBuilder(endpoint)
              .timeout(timeout)
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json; charset=UTF-8")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      // OAuth refresh may outlast the login session; recheck at the actual submission boundary.
      if (!stillActive.getAsBoolean()) return new Result(false, "INACTIVE_SUBSCRIPTION", false, 0);
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status >= 200 && status < 300) {
        try {
          JsonNode name = json.readTree(response.body()).path("name");
          if (name.isString() && !name.asText().isBlank())
            return new Result(true, "ACCEPTED", false, 0);
        } catch (RuntimeException ignored) {
        }
        return new Result(false, "INVALID_RESPONSE", true, 60);
      }
      String code = errorCode(response.body(), status);
      boolean retryable = status == 429 || status >= 500;
      return new Result(
          false,
          code,
          retryable,
          retryable ? retryAfter(response.headers().firstValue("Retry-After").orElse("")) : 0);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Result(false, "INTERRUPTED", true, 60);
    } catch (IOException e) {
      return new Result(false, "CONNECTION_FAILED", true, 60);
    }
  }

  private String errorCode(String body, int status) {
    try {
      JsonNode error = json.readTree(body).path("error");
      for (JsonNode detail : error.path("details")) {
        if ("type.googleapis.com/google.firebase.fcm.v1.FcmError"
            .equals(detail.path("@type").asText())) {
          String code = detail.path("errorCode").asText();
          if (ERROR_CODES.contains(code) && (!"UNREGISTERED".equals(code) || status == 404))
            return code;
        }
      }
      String code = error.path("status").asText();
      // A generic 404 does not prove an unregistered installation.
      if (ERROR_CODES.contains(code) && !"UNREGISTERED".equals(code)) return code;
    } catch (RuntimeException ignored) {
    }
    return "HTTP_" + status;
  }

  private long retryAfter(String value) {
    try {
      return Math.max(60, Long.parseLong(value));
    } catch (NumberFormatException ignored) {
      try {
        return Math.max(
            60,
            Duration.between(
                    clock.instant(),
                    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
                .getSeconds());
      } catch (RuntimeException invalidDate) {
        return 60;
      }
    }
  }

  static URI endpoint(String projectId) {
    if (projectId == null || !projectId.matches("[a-z][a-z0-9-]{4,28}[a-z0-9]"))
      throw new IllegalArgumentException("FCM project ID is invalid");
    return URI.create("https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send");
  }

  static URI frontendOrigin(String value) {
    URI uri;
    try {
      uri = URI.create(value);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "FCM frontend URL must be an HTTPS origin or local HTTP origin");
    }
    boolean localHttp =
        "http".equals(uri.getScheme())
            && uri.getHost() != null
            && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
    if ((!"https".equals(uri.getScheme()) && !localHttp)
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
        || uri.getPort() == 0
        || uri.getPort() > 65535)
      throw new IllegalArgumentException(
          "FCM frontend URL must be an HTTPS origin or local HTTP origin");
    return uri;
  }

  static GoogleCredentials loadCredentials(String projectId, String path) {
    endpoint(projectId);
    try {
      GoogleCredentials loaded;
      if (path == null || path.isBlank()) {
        loaded = GoogleCredentials.getApplicationDefault();
      } else {
        Path file = Path.of(path);
        if (!file.isAbsolute() || !Files.isRegularFile(file) || !Files.isReadable(file))
          throw new IOException();
        try (var input = Files.newInputStream(file)) {
          loaded = ServiceAccountCredentials.fromStream(input);
        }
      }
      if (loaded instanceof ServiceAccountCredentials serviceAccount
          && (!projectId.equals(serviceAccount.getProjectId())
              || !URI.create("https://oauth2.googleapis.com/token")
                  .equals(serviceAccount.getTokenServerUri()))) throw new IOException();
      return loaded.createScoped(List.of(SCOPE));
    } catch (IOException | IllegalArgumentException e) {
      throw new IllegalStateException(
          "FCM credentials could not be loaded; check the service-account path and project");
    }
  }

  public record Result(boolean accepted, String code, boolean retryable, long retryAfterSeconds) {}
}
