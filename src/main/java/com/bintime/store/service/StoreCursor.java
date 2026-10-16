package com.bintime.store.service;

import static com.bintime.common.web.ApiBusinessException.require;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;

/** 커서는 정렬값과 ID를 함께 보관하며 다른 필터에서 재사용할 수 없다. */
final class StoreCursor {
  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String encode(String context, String value, String id) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((hash(context) + "|" + value + "|" + id).getBytes(StandardCharsets.UTF_8));
  }

  static String[] decode(String cursor, String context, boolean time) {
    if (cursor == null) return null;
    try {
      require(cursor.length() < 1024, 400, "INVALID_CURSOR");
      String[] p =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
              .split("\\|", -1);
      require(
          p.length == 3 && p[0].equals(hash(context)) && p[2].matches("[A-Za-z0-9_-]{1,64}"),
          400,
          "INVALID_CURSOR");
      if (time) OffsetDateTime.parse(p[1]);
      else require(p[1].equals(p[2]), 400, "INVALID_CURSOR");
      return p;
    } catch (RuntimeException e) {
      throw new com.bintime.common.web.ApiBusinessException(400, "INVALID_CURSOR");
    }
  }

  static int limit(Integer n) {
    require(n != null && n >= 1 && n <= 100, 400, "INVALID_LIMIT");
    return n;
  }
}
