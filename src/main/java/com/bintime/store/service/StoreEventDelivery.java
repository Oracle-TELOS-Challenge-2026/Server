package com.bintime.store.service;

import com.bintime.search.dto.CreateMatchJobRequest;
import com.bintime.search.service.MatchService;
import com.bintime.search.service.SearchIndexService;
import com.bintime.store.entity.StoreEvent;
import com.bintime.store.repository.StoreRepository;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 커밋된 store outbox만 읽는다. search의 영속 큐 접수까지가 이 소비자의 책임이다. */
@Service
@EnableScheduling
@Profile("!api-docs")
@ConditionalOnProperty(
    name = "bintime.store.event-delivery-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class StoreEventDelivery {
  private static final Logger log = LoggerFactory.getLogger(StoreEventDelivery.class);
  private final StoreRepository db;
  private final StoreSearchCatalogService catalog;
  private final MatchService matches;
  private final SearchIndexService index;
  private final TransactionTemplate tx;
  private final Clock clock;

  public StoreEventDelivery(
      StoreRepository db,
      StoreSearchCatalogService catalog,
      MatchService matches,
      SearchIndexService index,
      TransactionTemplate tx,
      @Qualifier("storeClock") Clock clock) {
    this.db = db;
    this.catalog = catalog;
    this.matches = matches;
    this.index = index;
    this.tx = tx;
    this.clock = clock;
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }

  private record Claim(String id, String storeId, String token) {}

  private Claim claim() {
    return tx.execute(
        t -> {
          var moment = now();
          var candidates =
              db.limited(
                  StoreEvent.class,
                  "where e.status in ('QUEUED','FAILED') and e.availableAt<=?1"
                      + " and (e.leaseUntil is null or e.leaseUntil<=?1) order by e.createdAt,e.id",
                  20,
                  moment);
          for (StoreEvent candidate : candidates) {
            StoreEvent e = db.lock(StoreEvent.class, candidate.id);
            // 후보 조회 뒤 다른 인스턴스가 선점했을 수 있으므로 잠금 안에서 다시 확인한다.
            if ("PROCESSED".equals(e.status)
                || e.availableAt.isAfter(moment)
                || e.leaseUntil != null && e.leaseUntil.isAfter(moment)) continue;
            e.leaseToken = UUID.randomUUID().toString();
            e.leaseUntil = moment.plusMinutes(2);
            e.attempts++;
            return new Claim(e.id, e.storeId, e.leaseToken);
          }
          return null;
        });
  }

  @Scheduled(fixedDelayString = "${bintime.store.event-delivery-delay-ms:1000}")
  public void deliver() {
    for (int count = 0; count < 20; count++) {
      Claim claim = claim();
      if (claim == null) return;
      try {
        // export는 별도 스냅샷 트랜잭션이다. 업무 잠금이나 외부 호출을 예약 트랜잭션에 넣지 않는다.
        var document = catalog.document(claim.storeId());
        tx.executeWithoutResult(
            t -> {
              StoreEvent e = db.lock(StoreEvent.class, claim.id());
              if (!claim.token().equals(e.leaseToken) || "PROCESSED".equals(e.status)) return;
              index.changed(document);
              if (Set.of("PUBLISHED", "CREATED").contains(e.type)) {
                var current = catalog.revalidate(List.of(e.slotId));
                // 철회·재발행 뒤 도착한 작업으로 예전 발행의 매칭을 새로 만들지 않는다.
                if (!current.isEmpty()
                    && Objects.equals(
                        current.getFirst().publicationVersion(), e.publicationVersion))
                  matches.submit(new CreateMatchJobRequest(e.id, e.slotId, e.publicationVersion));
              }
              // 동일 DB의 search 큐와 ack를 함께 커밋한다. 중간 실패 시 둘 다 롤백된다.
              e.status = "PROCESSED";
              e.leaseToken = null;
              e.leaseUntil = null;
              e.lastError = null;
            });
      } catch (RuntimeException failure) {
        tx.executeWithoutResult(
            t -> {
              StoreEvent e = db.lock(StoreEvent.class, claim.id());
              if (!claim.token().equals(e.leaseToken)) return;
              e.status = "FAILED";
              e.availableAt = now().plusSeconds(Math.min(300, 1L << Math.min(e.attempts, 9)));
              e.leaseToken = null;
              e.leaseUntil = null;
              // 개인정보/SQL 원문 대신 오류 종류만 보관한다.
              e.lastError = failure.getClass().getSimpleName();
            });
        log.warn(
            "Store event {} delivery failed: {}", claim.id(), failure.getClass().getSimpleName());
      }
    }
  }
}
