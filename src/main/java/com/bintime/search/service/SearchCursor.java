package com.bintime.search.service;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SearchCursor {
  private final byte[] key;
  private final JsonMapper json;
  private final Clock clock;

  public SearchCursor(
      JsonMapper json, Clock clock, @Value("${search.cursor-secret:}") String secret) {
    this.json = json;
    this.clock = clock;
    if (secret.isBlank()) {
      key = new byte[32];
      new SecureRandom().nextBytes(key);
    } else {
      if (secret.length() < 32)
        throw new IllegalArgumentException(
            "search.cursor-secret must contain at least 32 characters");
      key = secret.getBytes(StandardCharsets.UTF_8);
    }
  }

  public String encode(State state) {
    String body =
        Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(state));
    return body + "." + sign(body);
  }

  public State decode(String value) {
    try {
      if (value.length() > 20000) throw SearchFailure.bad("INVALID_CURSOR");
      var parts = value.split("\\.");
      if (parts.length != 2
          || !MessageDigest.isEqual(
              sign(parts[0]).getBytes(StandardCharsets.UTF_8),
              parts[1].getBytes(StandardCharsets.UTF_8))) throw SearchFailure.bad("INVALID_CURSOR");
      var state = json.readValue(Base64.getUrlDecoder().decode(parts[0]), State.class);
      if (state.expiresAt() <= clock.millis()) throw SearchFailure.bad("CURSOR_EXPIRED");
      return state;
    } catch (SearchFailure e) {
      throw e;
    } catch (Exception e) {
      throw SearchFailure.bad("INVALID_CURSOR");
    }
  }

  private String sign(String text) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public record State(
      String pit, List<Object> after, String queryHash, long expiresAt, long snapshotAt) {}
}
