package com.bintime.user.repository;

import com.bintime.user.entity.UserAccount;
import jakarta.persistence.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Repository;

@Repository
@Lazy
public class UserAccountRepository {
  @PersistenceContext private EntityManager em;

  public UserAccount find(String id) {
    return em.find(UserAccount.class, id);
  }

  public UserAccount lock(String id) {
    return em.find(UserAccount.class, id, LockModeType.PESSIMISTIC_WRITE);
  }
}
