package com.bintime.search.service;

import static com.bintime.common.web.ApiBusinessException.require;

import com.bintime.search.entity.Waitlist;
import com.bintime.search.repository.LegacyWaitlistRepository;
import java.time.OffsetDateTime;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 예약과 같은 트랜잭션에 참여하는 search 소유의 협력 서비스. */
@Service
@Lazy
public class WaitlistReservationService {
  private final LegacyWaitlistRepository legacy;
  private final com.bintime.search.repository.SearchRepository search;
  private final WaitlistService service;

  public WaitlistReservationService(
      com.bintime.search.repository.SearchRepository search,
      WaitlistService service,
      LegacyWaitlistRepository legacy) {
    this.legacy = legacy;
    this.search = search;
    this.service = service;
  }

  public record Candidate(
      String userId,
      String storeId,
      String serviceId,
      String category,
      OffsetDateTime start,
      OffsetDateTime end,
      long price,
      double latitude,
      double longitude) {}

  @Transactional(propagation = Propagation.MANDATORY)
  public void fulfill(String id, Candidate c, OffsetDateTime now, String reservationId) {
    apply(id, c, now, reservationId, true);
  }

  /** 승인 대기 신청은 자격만 확인한다. 대기를 완료하거나 시간을 선점하지 않는다. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void validate(String id, Candidate c, OffsetDateTime now) {
    apply(id, c, now, null, false);
  }

  private void apply(
      String id, Candidate c, OffsetDateTime now, String reservationId, boolean fulfill) {
    Waitlist w = legacy.lock(id);
    if (w == null) {
      fulfillIntent(id, c, now, reservationId, fulfill);
      return;
    }
    require(w.userId.equals(c.userId), 403, "WAITLIST_FORBIDDEN");
    require("ACTIVE".equals(w.status) && w.end.isAfter(now), 409, "WAITLIST_INACTIVE");
    require(
        (w.storeId == null || w.storeId.equals(c.storeId))
            && (w.serviceId == null || w.serviceId.equals(c.serviceId))
            && w.category.equals(c.category)
            && !c.start.isBefore(w.start)
            && !c.end.isAfter(w.end)
            && (w.maxPrice == null || c.price <= w.maxPrice),
        409,
        "WAITLIST_CONDITION_MISMATCH");
    if (w.radius != null)
      require(
          distance(w.latitude, w.longitude, c.latitude, c.longitude) <= w.radius,
          409,
          "WAITLIST_CONDITION_MISMATCH");
    if (fulfill) {
      w.status = "FULFILLED";
      w.version++;
    }
  }

  private void fulfillIntent(
      String id, Candidate c, OffsetDateTime now, String reservationId, boolean fulfill) {
    // search HTTP API가 만든 대기는 search 소유의 잠금과 완료 메서드를 사용한다.
    // store의 고객 → 매장 → 슬롯 잠금 뒤에만 이 경계로 들어온다.
    search.lockUser(c.userId());
    var i =
        search
            .find(id)
            .orElseThrow(
                () -> new com.bintime.common.web.ApiBusinessException(404, "WAITLIST_NOT_FOUND"));
    require(i.userId().equals(c.userId()), 403, "WAITLIST_FORBIDDEN");
    var w = i.conditions();
    require(
        "ACTIVE".equals(i.status()) && i.consentAt() > 0 && w.windowEnd().isAfter(now),
        409,
        "WAITLIST_INACTIVE");
    require(
        (w.storeId() == null || w.storeId().equals(c.storeId()))
            && (w.serviceId() == null || w.serviceId().equals(c.serviceId()))
            && w.category().equals(c.category())
            && !c.start().isBefore(w.windowStart())
            && !c.end().isAfter(w.windowEnd())
            && (w.maxPrice() == null || c.price() <= w.maxPrice()),
        409,
        "WAITLIST_CONDITION_MISMATCH");
    if (w.location() != null && w.radiusMeters() != null)
      require(
          distance(w.location().latitude(), w.location().longitude(), c.latitude(), c.longitude())
              <= w.radiusMeters(),
          409,
          "WAITLIST_CONDITION_MISMATCH");
    if (fulfill) service.fulfilled(c.userId(), id, i.version(), reservationId);
  }

  private double distance(double lat1, double lon1, double lat2, double lon2) {
    double a =
        Math.pow(Math.sin(Math.toRadians(lat2 - lat1) / 2), 2)
            + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(Math.toRadians(lon2 - lon1) / 2), 2);
    return 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1 - a)));
  }
}
