package com.bintime.store.repository;

import com.bintime.store.entity.*;
import jakarta.persistence.*;
import java.util.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Repository;

@Repository
@Lazy
public class StoreRepository {
  @PersistenceContext private EntityManager em;

  public <T> T find(Class<T> type, String id) {
    return em.find(type, id);
  }

  public <T> T lock(Class<T> type, String id) {
    T value = em.find(type, id, LockModeType.PESSIMISTIC_WRITE);
    if (value != null) em.refresh(value, LockModeType.PESSIMISTIC_WRITE);
    return value;
  }

  public void save(Object value) {
    em.persist(value);
  }

  public void flush() {
    em.flush();
  }

  public <T> List<T> query(Class<T> type, String where, Object... args) {
    var q = em.createQuery("select e from " + type.getSimpleName() + " e " + where, type);
    for (int i = 0; i < args.length; i++) q.setParameter(i + 1, args[i]);
    return q.getResultList();
  }

  public <T> List<T> limited(Class<T> type, String where, int max, Object... args) {
    var q = em.createQuery("select e from " + type.getSimpleName() + " e " + where, type);
    for (int i = 0; i < args.length; i++) q.setParameter(i + 1, args[i]);
    return q.setMaxResults(max).getResultList();
  }

  public int count(Class<?> type, String where, Object... args) {
    var q =
        em.createQuery("select count(e) from " + type.getSimpleName() + " e " + where, Long.class);
    for (int i = 0; i < args.length; i++) q.setParameter(i + 1, args[i]);
    return Math.toIntExact(q.getSingleResult());
  }

  public record ReservationCounts(int total, int upcoming, int canceled) {}

  public ReservationCounts reservationCounts(String userId, java.time.OffsetDateTime moment) {
    Object[] row =
        em.createQuery(
                "select count(e), coalesce(sum(case when e.status in ('CONFIRMED','PENDING') and"
                    + " e.end>?2 then 1 else 0 end),0), coalesce(sum(case when e.status in"
                    + " ('CANCELED','REJECTED','EXPIRED','WITHDRAWN') then 1 else 0 end),0) from"
                    + " Reservation e where e.userId=?1",
                Object[].class)
            .setParameter(1, userId)
            .setParameter(2, moment)
            .getSingleResult();
    return new ReservationCounts(
        Math.toIntExact(((Number) row[0]).longValue()),
        Math.toIntExact(((Number) row[1]).longValue()),
        Math.toIntExact(((Number) row[2]).longValue()));
  }
}
