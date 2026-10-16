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
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!api-docs")
public class UserAccountRepository {
  private final JdbcClient jdbc;

  public UserAccountRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Account> findByKakaoSubject(String subject) {
    return jdbc.sql("SELECT user_id, role_code FROM user_accounts WHERE kakao_subject = :subject")
        .param("subject", subject)
        .query((rs, row) -> new Account(rs.getString("user_id"), rs.getString("role_code")))
        .optional();
  }

  public Account insert(String id, String subject, String displayName) {
    jdbc.sql(
            """
            INSERT INTO user_accounts (user_id, kakao_subject, display_name, role_code)
            VALUES (:id, :subject, :name, 'CUSTOMER')
            """)
        .param("id", id)
        .param("subject", subject)
        .param("name", displayName)
        .update();
    return new Account(id, "CUSTOMER");
  }

  public record Account(String userId, String role) {}
}
