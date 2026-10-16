package com.bintime.user.service;

import com.bintime.common.auth.Actor;
import com.bintime.user.entity.UserAccount;
import com.bintime.user.repository.UserAccountRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Lazy
public class UserAccountAccess {
  private final UserAccountRepository db;

  public UserAccountAccess(UserAccountRepository db) {
    this.db = db;
  }

  @Transactional(readOnly = true)
  public Actor get(String id) {
    UserAccount a = db.find(id);
    if (a == null) throw new com.bintime.common.web.ApiBusinessException(401, "UNAUTHENTICATED");
    return new Actor(a.id, a.role, a.displayName);
  }

  /** 고객 잠금 행을 처음 만들 때도 같은 사용자의 요청이 직렬화되어야 한다. */
  public void lock(String id) {
    if (db.lock(id) == null)
      throw new com.bintime.common.web.ApiBusinessException(401, "UNAUTHENTICATED");
  }

  /** 매장 생성 트랜잭션에 참여하므로 등록 실패 시 권한 변경도 함께 롤백된다. */
  @Transactional
  public void grantOwner(String id) {
    UserAccount account = db.lock(id);
    if (account == null)
      throw new com.bintime.common.web.ApiBusinessException(401, "UNAUTHENTICATED");
    if (!"CUSTOMER".equals(account.role) && !"OWNER".equals(account.role))
      throw new com.bintime.common.web.ApiBusinessException(403, "FORBIDDEN");
    account.role = "OWNER";
  }
}
