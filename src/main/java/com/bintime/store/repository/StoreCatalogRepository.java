package com.bintime.store.repository;

import com.bintime.search.dto.WaitlistConditions;
import com.bintime.store.entity.Shop;
import com.bintime.store.entity.Slot;
import com.bintime.store.entity.StoreService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Repository;

/** 검색 공급은 store 원장에서 읽고, 예약된 시간과 겹치는 공급도 제외한다. */
@Repository
@Lazy
public class StoreCatalogRepository {
  @PersistenceContext private EntityManager em;

  public record Supply(Slot slot, Shop shop, StoreService service) {}

  private static final String SUPPLY =
      """
      select s, p, v from Slot s, Shop p, StoreService v
      where p.id=s.storeId and v.id=s.serviceId and v.storeId=p.id
      and s.status='AVAILABLE' and v.active='Y' and s.start>:now and s.end>s.start
      and not exists (select r.id from Reservation r where r.storeId=p.id
          and r.status='CONFIRMED' and r.start<s.end and r.end>s.start)
      """;

  public List<Supply> available(
      WaitlistConditions c, OffsetDateTime now, OffsetDateTime after, String id, int limit) {
    String where = " and p.category=:category and s.start>=:start and s.end<=:end";
    if (c.storeId() != null) where += " and p.id=:store";
    if (c.serviceId() != null) where += " and v.id=:service";
    if (c.maxPrice() != null) where += " and s.payable<=:price";
    if (after != null) where += " and (s.start>:after or (s.start=:after and s.id>:id))";
    var query =
        em.createQuery(SUPPLY + where + " order by s.start,s.id", Object[].class)
            .setParameter("now", now)
            .setParameter("category", c.category())
            .setParameter("start", c.windowStart())
            .setParameter("end", c.windowEnd());
    if (c.storeId() != null) query.setParameter("store", c.storeId());
    if (c.serviceId() != null) query.setParameter("service", c.serviceId());
    if (c.maxPrice() != null) query.setParameter("price", c.maxPrice());
    if (after != null) query.setParameter("after", after).setParameter("id", id);
    return query.setMaxResults(limit).getResultList().stream().map(this::supply).toList();
  }

  public List<Supply> revalidate(Collection<String> ids, OffsetDateTime now) {
    return em
        .createQuery(SUPPLY + " and s.id in :ids order by s.start,s.id", Object[].class)
        .setParameter("now", now)
        .setParameter("ids", ids)
        .getResultList()
        .stream()
        .map(this::supply)
        .toList();
  }

  public List<Supply> storeSupply(String id, OffsetDateTime now) {
    return em
        .createQuery(SUPPLY + " and p.id=:store order by s.start,s.id", Object[].class)
        .setParameter("now", now)
        .setParameter("store", id)
        .getResultList()
        .stream()
        .map(this::supply)
        .toList();
  }

  private Supply supply(Object[] row) {
    return new Supply((Slot) row[0], (Shop) row[1], (StoreService) row[2]);
  }
}
