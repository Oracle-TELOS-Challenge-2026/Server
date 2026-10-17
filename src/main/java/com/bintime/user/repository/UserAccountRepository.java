package com.bintime.user.repository;

import com.bintime.user.entity.UserAccount;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@Lazy
@Profile("!api-docs")
public class UserAccountRepository {
  @PersistenceContext private EntityManager em;

  private final JdbcClient jdbc;

  public UserAccountRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public UserAccount find(String id) {
    return em.find(UserAccount.class, id);
  }

  public UserAccount lock(String id) {
    return em.find(UserAccount.class, id, LockModeType.PESSIMISTIC_WRITE);
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

  public List<String> personalizedCandidates(String after, int limit) {
    return jdbc.sql(
            "SELECT user_id FROM user_accounts WHERE role_code IN ('CUSTOMER','OWNER') "
                + "AND (:after IS NULL OR user_id>:after) ORDER BY user_id FETCH FIRST "
                + limit
                + " ROWS ONLY")
        .param("after", after == null || after.isEmpty() ? null : after)
        .query(String.class)
        .list();
  }

  public boolean personalizedCandidateExists(String userId) {
    return jdbc.sql(
                "SELECT COUNT(*) FROM user_accounts WHERE user_id=:id AND role_code IN"
                    + " ('CUSTOMER','OWNER')")
            .param("id", userId)
            .query(Integer.class)
            .single()
        > 0;
  }
}
