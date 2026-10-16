package com.bintime.notification.service;

import static com.bintime.common.web.ApiBusinessException.require;

import com.bintime.common.dto.CursorPage;
import com.bintime.notification.contract.SearchNotificationGateway;
import com.bintime.notification.dto.DispatchNotificationRequest;
import com.bintime.notification.dto.DispatchNotificationResponse;
import com.bintime.notification.dto.NotificationListResponse;
import com.bintime.notification.dto.NotificationResponse;
import com.bintime.notification.dto.NotificationStreamPayload;
import com.bintime.notification.repository.NotificationRepository;
import com.bintime.notification.subscription.NotificationSubscriptions;
import com.bintime.search.service.ConditionRules;
import com.bintime.search.service.SearchFailure;
import com.bintime.search.service.WaitlistService;
import com.bintime.store.contract.SearchStoreCatalog;
import com.bintime.user.security.BintimePrincipal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("!api-docs")
public class NotificationService implements SearchNotificationGateway {
  private final NotificationRepository db;
  private final NotificationSubscriptions subscriptions;
  private final WaitlistService waitlists;
  private final SearchStoreCatalog stores;
  private final ConditionRules rules;
  private final TransactionTemplate tx;
  private final Clock clock;

  public NotificationService(
      NotificationRepository db,
      NotificationSubscriptions subscriptions,
      WaitlistService waitlists,
      SearchStoreCatalog stores,
      ConditionRules rules,
      TransactionTemplate tx,
      Clock clock) {
    this.db = db;
    this.subscriptions = subscriptions;
    this.waitlists = waitlists;
    this.stores = stores;
    this.rules = rules;
    this.tx = tx;
    this.clock = clock;
  }

  @Override
  public void dispatch(DispatchNotificationRequest request) {
    enqueue(request);
  }

  public DispatchNotificationResponse enqueue(DispatchNotificationRequest request) {
    validate(request);
    String existing = db.duplicate(request.userId(), request.slotId());
    if (existing != null) return new DispatchNotificationResponse(existing, "QUEUED");
    var targets = subscriptions.activeTargets(request.userId());
    if (targets.isEmpty()) return skipped();
    // Match storage records this validation snapshot. Search's public get enriches through store,
    // so calling it inside our write transaction would invert search/store lock order.
    // A later cancellation preserves this history; each push attempt rechecks current consent.
    var valid = eligible(request);
    if (valid == null) return skipped();
    var accepted =
        new DispatchNotificationRequest(
            request.eventId(),
            request.userId(),
            request.type(),
            request.slotId(),
            request.publicationVersion(),
            valid.matches(),
            request.expiresAt());
    return tx.execute(
        status -> {
          require(db.lockUser(request.userId()), 404, "NOT_FOUND");
          String duplicate = db.duplicate(request.userId(), request.slotId());
          if (duplicate != null) return new DispatchNotificationResponse(duplicate, "QUEUED");
          var now = OffsetDateTime.now(clock);
          OffsetDateTime expires =
              request.expiresAt() == null || request.expiresAt().isAfter(valid.slot().startAt())
                  ? valid.slot().startAt()
                  : request.expiresAt();
          if (!expires.isAfter(now)) return skipped();
          var notification =
              new NotificationResponse(
                  "noti_" + UUID.randomUUID(),
                  "VACANCY_MATCHED",
                  "빈자리 알림",
                  "신청한 조건에 맞는 빈자리가 생겼어요.",
                  new NotificationResponse.Target(
                      valid.slot().storeId(),
                      request.slotId(),
                      request.publicationVersion(),
                      valid.matches().stream()
                          .map(DispatchNotificationRequest.MatcheItem::waitlistId)
                          .toList()),
                  new NotificationResponse.Action("OPEN_SLOT", "/customer/waitlist"),
                  "AVAILABLE",
                  null,
                  now);
          db.insert(
              request.userId(),
              accepted,
              notification,
              expires,
              targets.stream().map(NotificationSubscriptions.Target::id).toList());
          // The user lock serializes creation/read events, so committed SSE sequences have no gaps.
          db.event(
              request.userId(),
              "notification.created",
              new NotificationStreamPayload.Created(notification),
              now);
          return new DispatchNotificationResponse(notification.id(), "QUEUED");
        });
  }

  /** ACTIVE waitlists can only be created/updated with explicit notification consent. */
  public Eligible eligible(DispatchNotificationRequest request) {
    if (request == null
        || (request.expiresAt() != null
            && !request.expiresAt().toInstant().isAfter(clock.instant()))) return null;
    var slot =
        stores.revalidate(List.of(request.slotId())).stream()
            .filter(s -> s.id().equals(request.slotId()))
            .filter(s -> Objects.equals(s.publicationVersion(), request.publicationVersion()))
            .findFirst()
            .orElse(null);
    if (slot == null) return null;
    var matches = new ArrayList<DispatchNotificationRequest.MatcheItem>();
    for (var match : request.matches()) {
      try {
        var waitlist = waitlists.get(request.userId(), match.waitlistId());
        if ("ACTIVE".equals(waitlist.status())
            && Objects.equals(match.waitlistVersion(), waitlist.version())
            && rules.matches(waitlist.conditions(), slot, clock.instant())) matches.add(match);
      } catch (SearchFailure failure) {
        if (failure.status.value() != 404) throw failure;
      }
    }
    return matches.isEmpty() ? null : new Eligible(slot, List.copyOf(matches));
  }

  public NotificationListResponse list(
      BintimePrincipal principal, boolean unread, String cursor, int limit) {
    String user = user(principal);
    require(limit >= 1 && limit <= 100, 400, "INVALID_LIMIT");
    var before = cursor == null ? null : db.find(user, cursor);
    require(cursor == null || before != null, 400, "INVALID_CURSOR");
    var alerts = db.list(user, unread, before, limit + 1);
    boolean more = alerts.size() > limit;
    var items =
        alerts.stream().limit(limit).map(NotificationRepository.Alert::notification).toList();
    var current = availability(items);
    return new NotificationListResponse(
        current, new CursorPage(more ? items.getLast().id() : null, more), db.unread(user));
  }

  public NotificationResponse read(BintimePrincipal principal, String id, Boolean read) {
    String user = user(principal);
    require(Boolean.TRUE.equals(read), 400, "INVALID_READ_STATE");
    var result =
        tx.execute(
            status -> {
              require(db.lockUser(user), 401, "UNAUTHORIZED");
              var alert = db.find(user, id);
              require(alert != null, 404, "NOT_FOUND");
              var now = OffsetDateTime.now(clock);
              if (db.read(user, id, now)) {
                db.event(
                    user, "notification.read", new NotificationStreamPayload.Read(id, now), now);
              }
              return db.find(user, id).notification();
            });
    return availability(List.of(result)).getFirst();
  }

  private List<NotificationResponse> availability(List<NotificationResponse> items) {
    if (items.isEmpty()) return items;
    List<SearchStoreCatalog.Slot> slots;
    boolean known;
    try {
      slots = stores.revalidate(items.stream().map(n -> n.target().slotId()).distinct().toList());
      known = true;
    } catch (SearchFailure failure) {
      if (!failure.retryable()) throw failure;
      slots = List.of();
      known = false;
    }
    List<SearchStoreCatalog.Slot> current = slots;
    boolean available = known;
    return items.stream()
        .map(
            n ->
                new NotificationResponse(
                    n.id(),
                    n.type(),
                    n.title(),
                    n.body(),
                    n.target(),
                    n.action(),
                    !available
                        ? "UNKNOWN"
                        : current.stream()
                                .anyMatch(
                                    s ->
                                        s.id().equals(n.target().slotId())
                                            && Objects.equals(
                                                s.publicationVersion(),
                                                n.target().publicationVersion()))
                            ? "AVAILABLE"
                            : "UNAVAILABLE",
                    n.readAt(),
                    n.createdAt()))
        .toList();
  }

  public static String user(BintimePrincipal principal) {
    require(principal != null && principal.userId() != null, 401, "UNAUTHORIZED");
    return principal.userId();
  }

  private static void validate(DispatchNotificationRequest request) {
    require(request != null, 400, "INVALID_NOTIFICATION");
    require(
        request.eventId() != null
            && !request.eventId().isBlank()
            && request.eventId().length() <= 128
            && id(request.userId())
            && id(request.slotId()),
        400,
        "INVALID_NOTIFICATION");
    require("VACANCY_MATCHED".equals(request.type()), 400, "INVALID_NOTIFICATION_TYPE");
    require(
        request.publicationVersion() == null || request.publicationVersion() >= 1,
        400,
        "INVALID_PUBLICATION_VERSION");
    require(
        request.matches() != null && !request.matches().isEmpty() && request.matches().size() <= 20,
        400,
        "INVALID_MATCHES");
    require(
        request.matches().stream()
            .allMatch(
                m ->
                    m != null
                        && id(m.waitlistId())
                        && m.waitlistVersion() != null
                        && m.waitlistVersion() >= 1),
        400,
        "INVALID_MATCHES");
    require(
        request.matches().stream()
                .map(DispatchNotificationRequest.MatcheItem::waitlistId)
                .distinct()
                .count()
            == request.matches().size(),
        400,
        "INVALID_MATCHES");
  }

  private static boolean id(String id) {
    return id != null && !id.isBlank() && id.length() <= 64;
  }

  private static DispatchNotificationResponse skipped() {
    return new DispatchNotificationResponse(null, "SKIPPED");
  }

  public record Eligible(
      SearchStoreCatalog.Slot slot, List<DispatchNotificationRequest.MatcheItem> matches) {}
}
