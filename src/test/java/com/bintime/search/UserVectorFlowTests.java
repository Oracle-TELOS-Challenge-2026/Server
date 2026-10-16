package com.bintime.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bintime.search.port.IntentAiPort;
import com.bintime.search.service.*;
import com.bintime.user.contract.SearchUserAccess;
import com.bintime.user.service.UserPreferenceService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:user-vector-flow;DB_CLOSE_DELAY=-1",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
@ActiveProfiles("test")
class UserVectorFlowTests {
  @Autowired JdbcTemplate db;
  @Autowired UserPreferenceService preferences;
  @Autowired PersonalizationService vectors;
  @Autowired SearchWorker worker;
  @Autowired SearchUserAccess userAccess;
  @Autowired TransactionTemplate tx;
  @MockitoBean IntentAiPort ai;
  static final String FIRST =
      "{\"취향\":\"네일에 관심이 있고 경력 3년 이상의 시술자를 원해요\",\"기타\":{\"메모\":[\"조용한 곳\",\"심플한 디자인\"]}}";
  static final String SECOND = "{\"freeText\":\"다채로운 아트를 선호해요\",\"unknownField\":true}";

  static float[] validVector() {
    float[] value = new float[1536];
    value[0] = 1;
    return value;
  }

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

  @BeforeEach
  void setup() {
    for (String table :
        List.of(
            "search_vector_generation",
            "search_work",
            "search_vector",
            "search_preference_revision",
            "user_accounts")) db.update("delete from " + table);
    db.update(
        "insert into user_accounts(user_id,display_name,role_code,preferences_json)"
            + " values('vector-user','테스트 고객','CUSTOMER','{}')");
    doReturn(validVector()).when(ai).embed(anyString());
  }

  @Test
  void arbitraryJsonIsLoadedThroughUserContractAndEmbeddedWithoutWaitlistData() {
    preferences.replace("vector-user", FIRST);
    assertThat(userAccess.explicitPreferences("vector-user")).isEqualTo(FIRST);
    assertThat(db.queryForObject("select count(*) from search_work", Integer.class)).isEqualTo(1);
    worker.poll();
    verify(ai).embed(FIRST);
    assertThat(vectors.vector("vector-user")).containsExactly(validVector());
    var row = db.queryForMap("select source_version,model,dimensions from search_vector");
    assertThat(((Number) row.get("SOURCE_VERSION")).longValue()).isEqualTo(1);
    assertThat(row.get("MODEL")).isEqualTo("text-embedding-3-small");
    assertThat(((Number) row.get("DIMENSIONS")).intValue()).isEqualTo(1536);
    preferences.replace("vector-user", FIRST);
    vectors.requestRefresh("vector-user");
    worker.poll();
    verify(ai, times(1)).embed(anyString());
    assertThat(db.queryForObject("select count(*) from search_vector", Integer.class)).isEqualTo(1);
  }

  @Test
  void preferenceAndOutboxRollbackTogether() {
    tx.executeWithoutResult(
        t -> {
          preferences.replace("vector-user", FIRST);
          t.setRollbackOnly();
        });
    assertThat(userAccess.explicitPreferences("vector-user")).isEqualTo("{}");
    assertThat(db.queryForObject("select count(*) from search_work", Integer.class)).isZero();
    assertThat(db.queryForObject("select count(*) from search_preference_revision", Integer.class))
        .isZero();
  }

  @Test
  void changedPreferencesDiscardOldResponseAndScheduleLatest() {
    preferences.replace("vector-user", FIRST);
    doAnswer(
            a -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              preferences.replace("vector-user", SECOND);
              return validVector();
            })
        .when(ai)
        .embed(FIRST);
    worker.poll();
    assertThat(db.queryForObject("select count(*) from search_vector", Integer.class)).isZero();
    worker.poll();
    assertThat(vectors.vector("vector-user")).containsExactly(validVector());
    verify(ai, times(1)).embed(FIRST);
    verify(ai, times(1)).embed(SECOND);
  }

  @Test
  void emptyPreferencesRemoveVectorWithoutCallingOpenAi() {
    preferences.replace("vector-user", FIRST);
    worker.poll();
    clearInvocations(ai);
    preferences.replace("vector-user", "{}");
    assertThat(vectors.vector("vector-user")).isNull();
    worker.poll();
    verifyNoInteractions(ai);
    assertThat(db.queryForObject("select count(*) from search_vector", Integer.class)).isZero();
  }

  @Test
  void rawDatabaseChangesAreDetectedEvenBeforeChangeNotification() {
    preferences.replace("vector-user", FIRST);
    worker.poll();
    db.update("update user_accounts set preferences_json=? where user_id='vector-user'", SECOND);
    assertThat(vectors.vector("vector-user")).isNull();
    vectors.requestRefresh("vector-user");
    worker.poll();
    verify(ai).embed(SECOND);
  }

  @Test
  void failedAuthenticationDoesNotRetryButRateLimitDoes() {
    preferences.replace("vector-user", FIRST);
    doThrow(new EmbeddingFailure("OPENAI_AUTHENTICATION_FAILED", false)).when(ai).embed(any());
    worker.poll();
    assertThat(db.queryForObject("select status from search_work", String.class))
        .isEqualTo("FAILED");
    assertThat(db.queryForObject("select attempts from search_work", Integer.class)).isEqualTo(1);
    assertThat(db.queryForObject("select last_error from search_work", String.class))
        .isEqualTo("OPENAI_AUTHENTICATION_FAILED");
    db.update("delete from search_work");
    doThrow(new EmbeddingFailure("OPENAI_RATE_LIMITED", true)).when(ai).embed(any());
    vectors.requestRefresh("vector-user");
    worker.poll();
    assertThat(db.queryForObject("select status from search_work", String.class))
        .isEqualTo("QUEUED");
  }

  @Test
  void invalidAndOversizedJsonCannotProduceAVector() {
    assertThatThrownBy(() -> preferences.replace("vector-user", "broken-json"))
        .hasMessage("INVALID_PREFERENCES_JSON");
    assertThatThrownBy(() -> preferences.replace("vector-user", "{} {}"))
        .hasMessage("INVALID_PREFERENCES_JSON");
    assertThat(db.queryForObject("select count(*) from search_work", Integer.class)).isZero();
    preferences.replace("vector-user", "{\"text\":\"" + "가".repeat(3000) + "\"}");
    worker.poll();
    verifyNoInteractions(ai);
    assertThat(db.queryForObject("select status from search_work", String.class))
        .isEqualTo("FAILED");
    assertThat(db.queryForObject("select last_error from search_work", String.class))
        .isEqualTo("PREFERENCES_INPUT_TOO_LARGE");
  }

  @Test
  void simultaneousGenerationCallsOpenAiOnce() throws Exception {
    preferences.replace("vector-user", FIRST);
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    doAnswer(
            a -> {
              entered.countDown();
              if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw new IllegalStateException("test timed out");
              return validVector();
            })
        .when(ai)
        .embed(anyString());
    try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var first = pool.submit(() -> vectors.refresh("vector-user"));
      try {
        assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> vectors.refresh("vector-user"))
            .hasMessage("USER_VECTOR_GENERATION_BUSY");
      } finally {
        release.countDown();
      }
      first.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    vectors.refresh("vector-user");
    verify(ai, times(1)).embed(FIRST);
    assertThat(db.queryForObject("select count(*) from search_vector_generation", Integer.class))
        .isZero();
  }

  @Test
  void expiredGenerationLeaseCanBeRecovered() {
    preferences.replace("vector-user", FIRST);
    db.update(
        "insert into search_vector_generation(user_id,lease_owner,lease_until)"
            + " values('vector-user','crashed-worker',0)");
    worker.poll();
    assertThat(vectors.vector("vector-user")).containsExactly(validVector());
    assertThat(db.queryForObject("select count(*) from search_vector_generation", Integer.class))
        .isZero();
  }

  @Test
  void workerThatLostItsLeaseCannotPersistOrClearNewOwnerLease() {
    preferences.replace("vector-user", FIRST);
    doAnswer(
            a -> {
              db.update(
                  "update search_vector_generation set lease_owner='new-owner' where"
                      + " user_id='vector-user'");
              return validVector();
            })
        .when(ai)
        .embed(anyString());
    vectors.refresh("vector-user");
    assertThat(db.queryForObject("select count(*) from search_vector", Integer.class)).isZero();
    assertThat(db.queryForObject("select lease_owner from search_vector_generation", String.class))
        .isEqualTo("new-owner");
  }

  @Test
  void busyGenerationDoesNotExhaustExternalRetryBudget() {
    preferences.replace("vector-user", FIRST);
    db.update(
        "insert into search_vector_generation(user_id,lease_owner,lease_until)"
            + " values('vector-user','other-worker',9999999999999)");
    for (int n = 0; n < 6; n++) {
      db.update("update search_work set available_at=0");
      worker.poll();
    }
    assertThat(db.queryForObject("select status from search_work", String.class))
        .isEqualTo("QUEUED");
    assertThat(db.queryForObject("select attempts from search_work", Integer.class)).isZero();
    verifyNoInteractions(ai);
  }
}
