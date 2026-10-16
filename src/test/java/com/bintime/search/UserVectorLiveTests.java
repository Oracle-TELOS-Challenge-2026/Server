package com.bintime.search;

import static org.assertj.core.api.Assertions.*;

import com.bintime.search.service.PersonalizationService;
import com.bintime.search.service.SearchWorker;
import com.bintime.user.service.UserPreferenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 명시적으로 켰을 때만 .env의 실제 키로 합성 선호 JSON 한 건을 전송한다. */
@SpringBootTest(
    properties = {
      "spring.config.import=optional:file:.env[.properties]",
      "spring.datasource.url=jdbc:h2:mem:user-vector-live;DB_CLOSE_DELAY=-1",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_OPENAI_INTEGRATION", matches = "1")
class UserVectorLiveTests {
  @Autowired JdbcTemplate db;
  @Autowired UserPreferenceService preferences;
  @Autowired PersonalizationService vectors;
  @Autowired SearchWorker worker;

  @Value("${OPENAI_API_KEY:}")
  String key;

  @org.springframework.test.context.DynamicPropertySource
  static void oracle(org.springframework.test.context.DynamicPropertyRegistry properties) {
    if (System.getenv("USER_VECTOR_ORACLE_URL") == null) return;
    properties.add("spring.datasource.url", () -> System.getenv("USER_VECTOR_ORACLE_URL"));
    properties.add("spring.datasource.username", () -> System.getenv("USER_VECTOR_ORACLE_USER"));
    properties.add(
        "spring.datasource.password", () -> System.getenv("USER_VECTOR_ORACLE_PASSWORD"));
    properties.add("spring.datasource.driver-class-name", () -> "oracle.jdbc.OracleDriver");
    properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    properties.add("spring.sql.init.mode", () -> "never");
  }

  @Test
  void dotenvKeyGeneratesRealVectorAndPersistsIt() {
    assertThat(!key.isBlank())
        .as("OPENAI_API_KEY must be configured; key value is never printed")
        .isTrue();
    db.update(
        "insert into user_accounts(user_id,display_name,role_code,preferences_json)"
            + " values('live-vector-user','합성 테스트 고객','CUSTOMER','{}')");
    String job = null;
    try {
      preferences.replace(
          "live-vector-user",
          "{\"preference\":\"저는 네일에 관심이 있고, 경력 3년 이상의 시술자가 있는 조용한 네일숍을 원해요.\"}");
      job =
          db
              .query(
                  "select id,payload from search_work where kind='VECTOR'",
                  (row, n) ->
                      "live-vector-user".equals(row.getString("payload"))
                          ? row.getString("id")
                          : null)
              .stream()
              .filter(java.util.Objects::nonNull)
              .findFirst()
              .orElseThrow();
      worker.run(job);
      String state =
          db.queryForObject("select status from search_work where id=?", String.class, job);
      String error =
          db.queryForObject("select last_error from search_work where id=?", String.class, job);
      assertThat(state)
          .as("Real OpenAI embedding work, safe error code: %s", error)
          .isEqualTo("SUCCEEDED");
      assertThat(vectors.vector("live-vector-user")).hasSize(1536);
      assertThat(
              db.queryForObject(
                  "select model from search_user_vector where user_id='live-vector-user'",
                  String.class))
          .isEqualTo("text-embedding-3-small");
    } finally {
      if (job != null) db.update("delete from search_work where id=?", job);
      db.update("delete from search_user_vector where user_id='live-vector-user'");
      db.update("delete from user_accounts where user_id='live-vector-user'");
    }
  }
}
