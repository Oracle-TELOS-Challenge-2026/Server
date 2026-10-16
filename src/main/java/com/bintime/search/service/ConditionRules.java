package com.bintime.search.service;

import com.bintime.search.dto.WaitlistConditions;
import com.bintime.store.contract.SearchStoreCatalog.Slot;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ConditionRules {
  public static final Set<String> CATEGORIES = Set.of("NAIL", "HAIR", "EYELASH", "SKIN");

  public WaitlistConditions normalize(WaitlistConditions c, Instant now) {
    if (c == null
        || !CATEGORIES.contains(Objects.toString(c.category(), ""))
        || c.windowStart() == null
        || c.windowEnd() == null
        || c.keywords() == null) throw SearchFailure.bad("INVALID_CONDITIONS");
    if (!c.windowStart().isBefore(c.windowEnd()) || !c.windowEnd().toInstant().isAfter(now))
      throw SearchFailure.bad("INVALID_WINDOW");
    if (c.storeId() != null && (c.storeId().isBlank() || c.storeId().length() > 64)
        || c.serviceId() != null && (c.serviceId().isBlank() || c.serviceId().length() > 64))
      throw SearchFailure.bad("INVALID_RESOURCE_ID");
    if (c.storeId() == null && (c.location() == null || c.radiusMeters() == null))
      throw SearchFailure.bad("LOCATION_REQUIRED");
    if (c.location() != null) coordinates(c.location().latitude(), c.location().longitude());
    if (c.radiusMeters() != null && (c.radiusMeters() < 100 || c.radiusMeters() > 10000)
        || c.maxPrice() != null && (c.maxPrice() < 0 || c.maxPrice() > 10000000))
      throw SearchFailure.bad("INVALID_BOUNDS");
    if (c.keywords().size() > 30
        || c.keywords().stream().anyMatch(k -> k == null || k.length() > 100))
      throw SearchFailure.bad("INVALID_KEYWORDS");
    return new WaitlistConditions(
        c.storeId(),
        c.category(),
        c.serviceId(),
        c.windowStart().withOffsetSameInstant(ZoneOffset.UTC),
        c.windowEnd().withOffsetSameInstant(ZoneOffset.UTC),
        c.location(),
        c.radiusMeters(),
        c.maxPrice(),
        c.keywords().stream()
            .map(String::trim)
            .filter(k -> !k.isEmpty())
            .distinct()
            .sorted()
            .toList());
  }

  public static void coordinates(Double lat, Double lon) {
    if (lat == null
        || lon == null
        || !Double.isFinite(lat)
        || !Double.isFinite(lon)
        || Math.abs(lat) > 90
        || Math.abs(lon) > 180) throw SearchFailure.bad("INVALID_COORDINATES");
  }

  public boolean matches(WaitlistConditions c, Slot s, Instant now) {
    return s.startAt().toInstant().isAfter(now)
        && !s.startAt().isBefore(c.windowStart())
        && !s.endAt().isAfter(c.windowEnd())
        && s.startAt().isBefore(s.endAt())
        && c.category().equals(s.category())
        && (c.storeId() == null || c.storeId().equals(s.storeId()))
        && (c.serviceId() == null || c.serviceId().equals(s.serviceId()))
        && (c.maxPrice() == null || s.payablePrice() <= c.maxPrice())
        && (c.location() == null
            || c.radiusMeters() == null
            || distance(
                    c.location().latitude(), c.location().longitude(), s.latitude(), s.longitude())
                <= c.radiusMeters());
  }

  public static double distance(double a, double b, double c, double d) {
    double x = Math.toRadians(c - a), y = Math.toRadians(d - b);
    double h =
        Math.pow(Math.sin(x / 2), 2)
            + Math.cos(Math.toRadians(a))
                * Math.cos(Math.toRadians(c))
                * Math.pow(Math.sin(y / 2), 2);
    return 6371008.8 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
  }
}
