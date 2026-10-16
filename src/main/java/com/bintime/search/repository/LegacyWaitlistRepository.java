package com.bintime.search.repository;

import com.bintime.search.entity.Waitlist;
import jakarta.persistence.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Repository;

@Repository
@Lazy
public class LegacyWaitlistRepository {
  @PersistenceContext private EntityManager em;

  public Waitlist lock(String id) {
    Waitlist w = em.find(Waitlist.class, id, LockModeType.PESSIMISTIC_WRITE);
    if (w != null) em.refresh(w, LockModeType.PESSIMISTIC_WRITE);
    return w;
  }
}
