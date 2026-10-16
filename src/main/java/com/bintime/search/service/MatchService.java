package com.bintime.search.service;

import com.bintime.search.dto.*;
import com.bintime.search.repository.SearchRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("!api-docs")
public class MatchService {
  private final SearchRepository db;
  private final TransactionTemplate tx;
  private final Clock clock;

  public MatchService(SearchRepository db, TransactionTemplate tx, Clock clock) {
    this.db = db;
    this.tx = tx;
    this.clock = clock;
  }

  /** store 커밋 이후 호출. HTTP 대신 단일 앱 내 공개 메서드로 사용할 수 있다. */
  public MatchJobResponse submit(CreateMatchJobRequest r) {
    if (r == null
        || r.eventId() == null
        || r.eventId().isBlank()
        || r.eventId().length() > 64
        || r.slotId() == null
        || r.slotId().isBlank()
        || r.slotId().length() > 64
        || r.publicationVersion() != null && r.publicationVersion() < 1)
      throw SearchFailure.bad("INVALID_EVENT");
    return tx.execute(
        t -> {
          db.lockUser("event:" + r.eventId());
          var rows =
              db.jdbc()
                  .queryForList(
                      "select job_id,slot_id,publication_version from search_match_job where"
                          + " event_id=?",
                      r.eventId());
          if (!rows.isEmpty()) {
            var row = rows.getFirst();
            Long version =
                row.get("PUBLICATION_VERSION") == null
                    ? null
                    : ((Number) row.get("PUBLICATION_VERSION")).longValue();
            if (!r.slotId().equals(row.get("SLOT_ID"))
                || !Objects.equals(version, r.publicationVersion()))
              throw SearchFailure.conflict("EVENT_CONFLICT");
            return new MatchJobResponse((String) row.get("JOB_ID"), "QUEUED");
          }
          String id = UUID.randomUUID().toString();
          db.jdbc()
              .update(
                  "insert into search_match_job(event_id,job_id,slot_id,publication_version,status)"
                      + " values(?,?,?,?,'QUEUED')",
                  r.eventId(),
                  id,
                  r.slotId(),
                  r.publicationVersion());
          db.enqueue("SLOT", db.encode(r), clock.millis());
          return new MatchJobResponse(id, "QUEUED");
        });
  }
}
