package com.bintime.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bintime.user.dto.SavePreferencesRequest;
import com.bintime.user.dto.UserProfileResponse;
import com.bintime.user.dto.UserProfileResponse.Preferences;
import com.bintime.user.repository.UserProfileRepository;
import com.bintime.user.security.BintimePrincipal;
import com.bintime.user.service.UserProfileException;
import com.bintime.user.service.UserProfileService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:profile-test;MODE=Oracle;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
      "bintime.auth.client-id=",
      "bintime.auth.client-secret=",
      "bintime.auth.frontend-url=http://localhost:5173"
    })
@ActiveProfiles("test")
class UserProfileTests {
  private static final String ME = "/api/v1/users/me";

  @Autowired WebApplicationContext context;
  @Autowired JdbcTemplate jdbc;
  @Autowired JsonMapper json;
  @Autowired com.bintime.search.service.SearchWorker worker;
  @Autowired com.bintime.search.service.PersonalizationService vectors;
  @Autowired UserProfileService profiles;
  @Autowired org.springframework.transaction.support.TransactionTemplate transactions;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.bintime.search.port.IntentAiPort ai;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.bintime.notification.subscription.NotificationSubscriptions subscriptions;

  private MockMvc mvc;
  private MockHttpSession session;
  private String csrfToken;

  @BeforeEach
  void setUp() throws Exception {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    // Only this test's isolated H2 database is initialized; application tables are never touched.
    jdbc.execute(
        """
        CREATE TABLE IF NOT EXISTS user_accounts (
          user_id VARCHAR(64) PRIMARY KEY,
          kakao_subject VARCHAR(100) UNIQUE,
          display_name VARCHAR(120) NOT NULL,
          role_code VARCHAR(8) DEFAULT 'CUSTOMER' NOT NULL,
          recommendation_opt_in CHAR(1) DEFAULT 'N' NOT NULL,
          recommendation_consent_at TIMESTAMP(6) WITH TIME ZONE,
          latitude NUMBER(9,6), longitude NUMBER(10,6),
          location_at TIMESTAMP(6) WITH TIME ZONE,
          accuracy_meters INTEGER, location_source VARCHAR(6),
          location_saved_at TIMESTAMP(6) WITH TIME ZONE,
          preferences_json CLOB DEFAULT '{"categories":[],"keywords":[],"maxPrice":null}' NOT NULL,
          version_no NUMBER(19,0) DEFAULT 1 NOT NULL,
          created_at TIMESTAMP(6) WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
        )
        """);
    jdbc.execute(
        """
        CREATE TABLE IF NOT EXISTS store_shops (
          store_id VARCHAR(64) PRIMARY KEY,
          owner_user_id VARCHAR(64) NOT NULL REFERENCES user_accounts(user_id)
        )
        """);
    jdbc.update("DELETE FROM store_shops");
    jdbc.update("DELETE FROM search_work");
    jdbc.update("DELETE FROM search_user_vector");
    jdbc.update("DELETE FROM user_accounts");
    jdbc.update(
        "INSERT INTO user_accounts (user_id, kakao_subject, display_name) VALUES (?, ?, ?)",
        "user-1",
        "1001",
        "첫 사용자");
    jdbc.update(
        "INSERT INTO user_accounts (user_id, kakao_subject, display_name) VALUES (?, ?, ?)",
        "user-2",
        "1002",
        "다른 사용자");
    var principal = new BintimePrincipal("user-1", "CUSTOMER");
    var authentication =
        new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "kakao");
    session = new MockHttpSession();
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        new SecurityContextImpl(authentication));
    var response =
        mvc.perform(get("/api/v1/auth/csrf").servletPath("/api/v1/auth/csrf").session(session))
            .andExpect(status().isOk())
            .andReturn();
    csrfToken =
        json.readTree(response.getResponse().getContentAsString())
            .at("/success/data/csrfToken")
            .asText();
  }

  @Test
  void readsActualAccountAndDefaultsWithoutInventedLocationOrStores() throws Exception {
    perform(HttpMethod.GET, ME, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resultType").value("SUCCESS"))
        .andExpect(jsonPath("$.success.data.id").value("user-1"))
        .andExpect(jsonPath("$.success.data.displayName").value("첫 사용자"))
        .andExpect(jsonPath("$.success.data.roles[0]").value("CUSTOMER"))
        .andExpect(jsonPath("$.success.data.ownedStoreIds").isEmpty())
        .andExpect(jsonPath("$.success.data.location").isEmpty())
        .andExpect(jsonPath("$.success.data.preferences.categories").isEmpty())
        .andExpect(jsonPath("$.success.data.preferences.keywords").isEmpty())
        .andExpect(jsonPath("$.success.data.preferences.maxPrice").isEmpty())
        .andExpect(jsonPath("$.success.data.preferences.text").value(""))
        .andExpect(jsonPath("$.success.data.version").value(1));
  }

  @Test
  void readsCurrentDatabaseRoleAndOnlyOwnedStores() throws Exception {
    jdbc.update("UPDATE user_accounts SET role_code = 'OWNER' WHERE user_id = 'user-1'");
    jdbc.update("INSERT INTO store_shops VALUES ('store-b', 'user-1')");
    jdbc.update("INSERT INTO store_shops VALUES ('store-a', 'user-1')");
    jdbc.update("INSERT INTO store_shops VALUES ('store-other', 'user-2')");

    perform(HttpMethod.GET, ME, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success.data.roles[0]").value("OWNER"))
        .andExpect(jsonPath("$.success.data.ownedStoreIds[0]").value("store-a"))
        .andExpect(jsonPath("$.success.data.ownedStoreIds[1]").value("store-b"))
        .andExpect(jsonPath("$.success.data.ownedStoreIds.length()").value(2));
  }

  @Test
  void displayNameIsTrimmedAndVersionPersists() throws Exception {
    perform(
            HttpMethod.PATCH,
            ME,
            """
            {"displayName":"  새 이름  ","expectedVersion":1}
            """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success.data.displayName").value("새 이름"))
        .andExpect(jsonPath("$.success.data.version").value(2));
    perform(HttpMethod.GET, ME, null)
        .andExpect(jsonPath("$.success.data.displayName").value("새 이름"))
        .andExpect(jsonPath("$.success.data.version").value(2));
  }

  @Test
  void displayNameLimitCountsUnicodeCodePoints() throws Exception {
    String name = "💅".repeat(30);
    perform(
            HttpMethod.PATCH,
            ME,
            json.writeValueAsString(Map.of("displayName", name, "expectedVersion", 1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success.data.displayName").value(name));
    perform(
            HttpMethod.PATCH,
            ME,
            json.writeValueAsString(Map.of("displayName", name + "가", "expectedVersion", 2)))
        .andExpect(status().isBadRequest());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"expectedVersion\":1}",
        "{\"displayName\":\"  \",\"expectedVersion\":1}",
        "{\"displayName\":\"이름\"}",
        "{\"displayName\":\"이름\",\"expectedVersion\":0}",
        "{\"displayName\":\"이름\",\"expectedVersion\":1.5}",
        "{\"displayName\":\"이름\",\"expectedVersion\":\"1\"}",
        "{\"displayName\":\"이름\",\"expectedVersion\":\"invalid\"}"
      })
  void rejectsInvalidProfileInputWithoutChanges(String body) throws Exception {
    perform(HttpMethod.PATCH, ME, body)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    assertThat(
            jdbc.queryForObject(
                "SELECT version_no FROM user_accounts WHERE user_id = 'user-1'", Long.class))
        .isEqualTo(1);
  }

  @Test
  void profileAndPreferencesShareOneOptimisticVersion() throws Exception {
    perform(
            HttpMethod.PATCH,
            ME,
            """
            {"displayName":"변경한 이름","expectedVersion":1}
            """)
        .andExpect(status().isOk());
    String preferences =
        """
        {"categories":["NAIL","HAIR"],"keywords":["심플"],"maxPrice":60000,"expectedVersion":%d}
        """;
    perform(HttpMethod.PUT, ME + "/preferences", preferences.formatted(1))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    perform(HttpMethod.PUT, ME + "/preferences", preferences.formatted(2))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success.data.version").value(3));
    perform(
            HttpMethod.PATCH,
            ME,
            """
            {"displayName":"뒤늦은 변경","expectedVersion":2}
            """)
        .andExpect(status().isConflict());
    perform(HttpMethod.GET, ME, null)
        .andExpect(jsonPath("$.success.data.displayName").value("변경한 이름"))
        .andExpect(jsonPath("$.success.data.preferences.categories[1]").value("HAIR"))
        .andExpect(jsonPath("$.success.data.preferences.keywords[0]").value("심플"))
        .andExpect(jsonPath("$.success.data.preferences.maxPrice").value(60000))
        .andExpect(jsonPath("$.success.data.version").value(3));
  }

  @Test
  void simultaneousProfileAndPreferencesUpdatesHaveOnlyOneWinner() throws Exception {
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var profile =
          workers.submit(
              () -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return perform(
                        HttpMethod.PATCH,
                        ME,
                        """
                        {"displayName":"경합 이름","expectedVersion":1}
                        """)
                    .andReturn()
                    .getResponse()
                    .getStatus();
              });
      var preferences =
          workers.submit(
              () -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return perform(
                        HttpMethod.PUT,
                        ME + "/preferences",
                        """
                        {"categories":["NAIL"],"keywords":[],"maxPrice":null,"expectedVersion":1}
                        """)
                    .andReturn()
                    .getResponse()
                    .getStatus();
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(List.of(profile.get(10, TimeUnit.SECONDS), preferences.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
    }
    perform(HttpMethod.GET, ME, null).andExpect(jsonPath("$.success.data.version").value(2));
  }

  @Test
  void preferencesReplacePreviousValuesAndAllowNoPriceLimit() throws Exception {
    perform(
            HttpMethod.PUT,
            ME + "/preferences",
            """
            {"categories":["NAIL","HAIR","EYELASH","SKIN"],"keywords":["차분함"],"maxPrice":0,"expectedVersion":1}
            """)
        .andExpect(status().isOk());
    perform(
            HttpMethod.PUT,
            ME + "/preferences",
            """
            {"categories":[],"keywords":[],"maxPrice":null,"expectedVersion":2}
            """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success.data.categories").isEmpty())
        .andExpect(jsonPath("$.success.data.keywords").isEmpty())
        .andExpect(jsonPath("$.success.data.maxPrice").isEmpty())
        .andExpect(jsonPath("$.success.data.text").value(""))
        .andExpect(jsonPath("$.success.data.version").value(3));
  }

  @Test
  void preferenceTextPreservesOriginalWhitespaceAndOmissionButExplicitEmptyClearsIt()
      throws Exception {
    String original = "  네일은 심플하게\n색상은 파스텔로 부탁해요.\t\n ";
    perform(HttpMethod.PUT, ME + "/preferences", preferencesWithText(original, 1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success.data.text").value(original))
        .andExpect(jsonPath("$.success.data.version").value(2));
    perform(HttpMethod.GET, ME, null)
        .andExpect(jsonPath("$.success.data.preferences.text").value(original));

    perform(
            HttpMethod.PUT,
            ME + "/preferences",
            """
            {"categories":["HAIR"],"keywords":["차분함"],"maxPrice":null,"expectedVersion":2}
            """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success.data.text").value(original))
        .andExpect(jsonPath("$.success.data.categories[0]").value("HAIR"))
        .andExpect(jsonPath("$.success.data.version").value(3));
    perform(HttpMethod.GET, ME, null)
        .andExpect(jsonPath("$.success.data.preferences.text").value(original));

    perform(HttpMethod.PUT, ME + "/preferences", preferencesWithText("", 3))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success.data.text").value(""))
        .andExpect(jsonPath("$.success.data.version").value(4));
    perform(HttpMethod.GET, ME, null)
        .andExpect(jsonPath("$.success.data.preferences.text").value(""));
  }

  @Test
  void preferenceTextAllows2000Utf16UnitsAndRejects2001WithoutChangingDataOrVersion()
      throws Exception {
    String allowed = "💅".repeat(1000);
    assertThat(allowed.length()).isEqualTo(2000);
    perform(HttpMethod.PUT, ME + "/preferences", preferencesWithText(allowed, 1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success.data.text").value(allowed))
        .andExpect(jsonPath("$.success.data.version").value(2));
    JsonNode before = data(perform(HttpMethod.GET, ME, null));

    perform(HttpMethod.PUT, ME + "/preferences", preferencesWithText(allowed + "가", 2))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    assertThat(data(perform(HttpMethod.GET, ME, null))).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "123", "true", "[]", "{}"})
  void rejectsExplicitNullAndNonStringPreferenceTextWithoutChangingDataOrVersion(String textJson)
      throws Exception {
    perform(HttpMethod.PUT, ME + "/preferences", preferencesWithText("기존 취향", 1))
        .andExpect(status().isOk());
    JsonNode before = data(perform(HttpMethod.GET, ME, null));

    perform(
            HttpMethod.PUT,
            ME + "/preferences",
            """
            {"categories":["HAIR"],"keywords":[],"maxPrice":0,"expectedVersion":2,"text":%s}
            """
                .formatted(textJson))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    assertThat(data(perform(HttpMethod.GET, ME, null))).isEqualTo(before);
  }

  @Test
  void staleVersionCannotOverwritePreferenceText() throws Exception {
    perform(HttpMethod.PUT, ME + "/preferences", preferencesWithText("최신 취향", 1))
        .andExpect(status().isOk());
    JsonNode before = data(perform(HttpMethod.GET, ME, null));

    perform(HttpMethod.PUT, ME + "/preferences", preferencesWithText("늦게 도착한 취향", 1))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    assertThat(data(perform(HttpMethod.GET, ME, null))).isEqualTo(before);
  }

  @Test
  void omittedTextCannotUseAnEarlierSnapshotEvenIfAnotherUpdateCouldReachTheRequestedVersion() {
    var repository = mock(UserProfileRepository.class);
    var earlier =
        new UserProfileResponse(
            "user-1",
            "첫 사용자",
            List.of("CUSTOMER"),
            List.of(),
            null,
            new Preferences(List.of(), List.of(), null, "textA"),
            1L);
    when(repository.findProfile("user-1")).thenReturn(Optional.of(earlier));
    when(repository.exists("user-1")).thenReturn(true);
    // A concurrent textB update could make this future version match after textA was read.
    when(repository.updatePreferences(anyString(), any(Preferences.class), anyLong()))
        .thenReturn(1);
    var service =
        new UserProfileService(
            repository, mock(org.springframework.context.ApplicationEventPublisher.class));
    var request = new SavePreferencesRequest(List.of(), List.of(), null, null, 2L);

    assertThatThrownBy(() -> service.savePreferences("user-1", request))
        .isInstanceOfSatisfying(
            UserProfileException.class,
            error -> {
              assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(error.code()).isEqualTo("VERSION_CONFLICT");
            });
    verify(repository, never()).updatePreferences(anyString(), any(Preferences.class), anyLong());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"categories\":[],\"keywords\":[],\"expectedVersion\":1}",
        "{\"categories\":null,\"keywords\":[],\"maxPrice\":null,\"expectedVersion\":1}",
        "{\"categories\":[\"OTHER\"],\"keywords\":[],\"maxPrice\":null,\"expectedVersion\":1}",
        "{\"categories\":[null],\"keywords\":[],\"maxPrice\":null,\"expectedVersion\":1}",
        "{\"categories\":[],\"keywords\":[\"  \"],\"maxPrice\":null,\"expectedVersion\":1}",
        "{\"categories\":[],\"keywords\":[null],\"maxPrice\":null,\"expectedVersion\":1}",
        "{\"categories\":[],\"keywords\":[],\"maxPrice\":-1,\"expectedVersion\":1}",
        "{\"categories\":[],\"keywords\":[],\"maxPrice\":10000001,\"expectedVersion\":1}",
        "{\"categories\":[],\"keywords\":[],\"maxPrice\":100.5,\"expectedVersion\":1}",
        "{\"categories\":[],\"keywords\":[],\"maxPrice\":\"100\",\"expectedVersion\":1}",
        "{\"categories\":[],\"keywords\":[],\"maxPrice\":null,\"expectedVersion\":1.5}"
      })
  void rejectsInvalidPreferencesWithoutPartialUpdate(String body) throws Exception {
    perform(HttpMethod.PUT, ME + "/preferences", body)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    perform(HttpMethod.GET, ME, null)
        .andExpect(jsonPath("$.success.data.preferences.categories").isEmpty())
        .andExpect(jsonPath("$.success.data.version").value(1));
  }

  @Test
  void locationRoundTripsAndOldObservationCannotReplaceNewerLocation() throws Exception {
    var first =
        perform(HttpMethod.PUT, ME + "/location", location(37.5, "2026-10-16T14:00:00+09:00"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success.data.source").value("DEVICE"))
            .andExpect(jsonPath("$.success.data.accuracyMeters").value(30));
    JsonNode saved = data(first);
    assertThat(OffsetDateTime.parse(saved.get("observedAt").asText()).toInstant())
        .isEqualTo(OffsetDateTime.parse("2026-10-16T05:00:00Z").toInstant());
    assertThat(saved.get("savedAt").asText()).isNotBlank();
    perform(HttpMethod.PUT, ME + "/location", location(38.0, "2026-10-16T04:59:59Z"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("STALE_LOCATION"));
    perform(HttpMethod.GET, ME, null)
        .andExpect(jsonPath("$.success.data.location.latitude").value(37.5))
        .andExpect(jsonPath("$.success.data.version").value(1));

    // The same instant in a different time zone is not a stale observation.
    perform(HttpMethod.PUT, ME + "/location", location(38.0, "2026-10-16T05:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success.data.latitude").value(38.0));
    perform(
            HttpMethod.PUT,
            ME + "/location",
            """
            {"latitude":39,"longitude":127,"source":"MANUAL","observedAt":"2026-10-16T06:00:00Z"}
            """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success.data.source").value("MANUAL"))
        .andExpect(jsonPath("$.success.data.accuracyMeters").isEmpty());
    perform(HttpMethod.GET, ME, null)
        .andExpect(jsonPath("$.success.data.location.latitude").value(39.0))
        .andExpect(jsonPath("$.success.data.version").value(1));
  }

  @Test
  void deletesEveryLocationFieldIdempotentlyWithoutChangingProfileVersion() throws Exception {
    perform(HttpMethod.PUT, ME + "/location", location(37.5, "2026-10-16T05:00:00Z"))
        .andExpect(status().isOk());
    for (int attempt = 0; attempt < 2; attempt++) {
      var response =
          perform(HttpMethod.DELETE, ME + "/location", null)
              .andExpect(status().isNoContent())
              .andReturn()
              .getResponse();
      assertThat(response.getContentAsString()).isEmpty();
    }
    perform(HttpMethod.GET, ME, null)
        .andExpect(jsonPath("$.success.data.location").isEmpty())
        .andExpect(jsonPath("$.success.data.version").value(1));
    var fields =
        jdbc.queryForMap(
            "SELECT latitude, longitude, location_at, accuracy_meters, location_source,"
                + " location_saved_at FROM user_accounts WHERE user_id = 'user-1'");
    assertThat(fields.values()).allMatch(value -> value == null);
  }

  @Test
  void simultaneousLocationUpdatesAlwaysKeepTheNewestObservation() throws Exception {
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var older =
          workers.submit(
              () -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return perform(
                        HttpMethod.PUT, ME + "/location", location(37.5, "2026-10-16T05:00:00Z"))
                    .andReturn()
                    .getResponse()
                    .getStatus();
              });
      var newer =
          workers.submit(
              () -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return perform(
                        HttpMethod.PUT, ME + "/location", location(38.0, "2026-10-16T05:00:01Z"))
                    .andReturn()
                    .getResponse()
                    .getStatus();
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      // The older request may commit first or lose; the newer request must always succeed.
      assertThat(older.get(10, TimeUnit.SECONDS)).isIn(200, 409);
      assertThat(newer.get(10, TimeUnit.SECONDS)).isEqualTo(200);
    }
    var response =
        perform(HttpMethod.GET, ME, null)
            .andExpect(jsonPath("$.success.data.location.latitude").value(38.0))
            .andExpect(jsonPath("$.success.data.version").value(1));
    assertThat(OffsetDateTime.parse(data(response).at("/location/observedAt").asText()).toInstant())
        .isEqualTo(OffsetDateTime.parse("2026-10-16T05:00:01Z").toInstant());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"latitude\":91,\"longitude\":127,\"source\":\"DEVICE\",\"observedAt\":\"2026-10-16T05:00:00Z\"}",
        "{\"latitude\":-91,\"longitude\":127,\"source\":\"DEVICE\",\"observedAt\":\"2026-10-16T05:00:00Z\"}",
        "{\"latitude\":37,\"longitude\":181,\"source\":\"DEVICE\",\"observedAt\":\"2026-10-16T05:00:00Z\"}",
        "{\"latitude\":37,\"longitude\":127,\"source\":\"DEVICE\",\"accuracyMeters\":-1,\"observedAt\":\"2026-10-16T05:00:00Z\"}",
        "{\"latitude\":37,\"longitude\":127,\"source\":\"DEVICE\",\"accuracyMeters\":30.5,\"observedAt\":\"2026-10-16T05:00:00Z\"}",
        "{\"latitude\":37,\"longitude\":127,\"source\":\"DEVICE\",\"accuracyMeters\":\"30\",\"observedAt\":\"2026-10-16T05:00:00Z\"}",
        "{\"latitude\":37,\"longitude\":127,\"source\":\"UNKNOWN\",\"observedAt\":\"2026-10-16T05:00:00Z\"}",
        "{\"latitude\":37,\"longitude\":127,\"source\":\"DEVICE\",\"observedAt\":\"2026-10-16T05:00:00\"}"
      })
  void rejectsInvalidLocationWithoutSavingIt(String body) throws Exception {
    perform(HttpMethod.PUT, ME + "/location", body).andExpect(status().isBadRequest());
    perform(HttpMethod.GET, ME, null).andExpect(jsonPath("$.success.data.location").isEmpty());
  }

  @Test
  void sessionIdentityPreventsTargetingAnotherUserOrAssigningRoles() throws Exception {
    var response =
        perform(
                HttpMethod.PATCH,
                ME,
                """
                {"userId":"user-2","role":"OWNER","roles":["OWNER"],"displayName":"내 이름","expectedVersion":1}
                """)
            .andReturn()
            .getResponse();
    // Unknown fields may be ignored or rejected, but they must never become identity/role inputs.
    assertThat(response.getStatus()).isIn(200, 400);
    assertThat(
            jdbc.queryForObject(
                "SELECT display_name FROM user_accounts WHERE user_id = 'user-2'", String.class))
        .isEqualTo("다른 사용자");
    assertThat(
            jdbc.queryForObject(
                "SELECT role_code FROM user_accounts WHERE user_id = 'user-1'", String.class))
        .isEqualTo("CUSTOMER");
  }

  @Test
  void requiresAuthenticationAndRealSessionCsrfHeaderForWrites() throws Exception {
    mvc.perform(get(ME).servletPath(ME))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    mvc.perform(
            patch(ME)
                .servletPath(ME)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"이름\",\"expectedVersion\":1}"))
        .andExpect(status().isUnauthorized());
    for (String suppliedToken : List.of("", "invalid-token")) {
      var request =
          patch(ME)
              .servletPath(ME)
              .session(session)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"displayName\":\"이름\",\"expectedVersion\":1}");
      if (!suppliedToken.isEmpty()) request.header("X-CSRF-Token", suppliedToken);
      mvc.perform(request)
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
    }
  }

  @Test
  void staleSessionCannotReadOrModifyADeletedAccount() throws Exception {
    jdbc.update("DELETE FROM user_accounts WHERE user_id = 'user-1'");
    perform(HttpMethod.GET, ME, null).andExpect(status().isUnauthorized());
    perform(
            HttpMethod.PATCH,
            ME,
            """
            {"displayName":"이름","expectedVersion":1}
            """)
        .andExpect(status().isUnauthorized());
    perform(
            HttpMethod.PUT,
            ME + "/preferences",
            """
            {"categories":[],"keywords":[],"maxPrice":null,"expectedVersion":1}
            """)
        .andExpect(status().isUnauthorized());
    perform(HttpMethod.PUT, ME + "/location", location(37.5, "2026-10-16T05:00:00Z"))
        .andExpect(status().isUnauthorized());
    perform(HttpMethod.DELETE, ME + "/location", null)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
  }

  private ResultActions perform(HttpMethod method, String path, String body) throws Exception {
    var request = MockMvcRequestBuilders.request(method, path).servletPath(path).session(session);
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
    if (method != HttpMethod.GET) request.header("X-CSRF-Token", csrfToken);
    return mvc.perform(request);
  }

  private float[] preferenceVector() {
    float[] vector = new float[1536];
    vector[0] = 1;
    return vector;
  }

  @Test
  void preferenceApiQueuesEmbeddingAfterCommitAndPersistsUserVector() throws Exception {
    when(ai.embed(anyString())).thenReturn(preferenceVector());
    perform(HttpMethod.PUT, ME + "/preferences", preferencesWithText("조용한 원컬러 네일", 1))
        .andExpect(status().isOk());
    org.mockito.Mockito.verifyNoInteractions(ai);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM search_work WHERE kind='VECTOR' AND status='QUEUED'",
                Integer.class))
        .isEqualTo(1);
    assertThat(vectors.vector("user-1")).isNull();
    String saved =
        jdbc.queryForObject(
            "SELECT preferences_json FROM user_accounts WHERE user_id='user-1'", String.class);
    worker.poll();
    verify(ai).embed(saved);
    assertThat(vectors.vector("user-1")).containsExactly(preferenceVector());
    assertThat(vectors.vector("user-2")).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT dimensions FROM search_user_vector WHERE user_id='user-1'", Integer.class))
        .isEqualTo(1536);
    assertThat(
            jdbc.queryForObject(
                "SELECT model FROM search_user_vector WHERE user_id='user-1'", String.class))
        .isEqualTo("text-embedding-3-small");

    perform(HttpMethod.PUT, ME + "/preferences", preferencesWithText("조용한 원컬러 네일", 2))
        .andExpect(status().isOk());
    worker.poll();
    verify(ai, org.mockito.Mockito.times(1)).embed(anyString());
    assertThat(vectors.vector("user-1")).containsExactly(preferenceVector());

    perform(HttpMethod.PUT, ME + "/preferences", preferencesWithText("화려한 아트", 3))
        .andExpect(status().isOk());
    assertThat(vectors.vector("user-1")).isNull();
    worker.poll();
    verify(ai, org.mockito.Mockito.times(2)).embed(anyString());
    assertThat(vectors.vector("user-1")).containsExactly(preferenceVector());
  }

  @Test
  void openAiFailureDoesNotUndoSavedPreferencesAndWorkerRetries() throws Exception {
    when(ai.embed(anyString()))
        .thenThrow(new com.bintime.search.service.EmbeddingFailure("AI_UNAVAILABLE", true))
        .thenReturn(preferenceVector());
    perform(HttpMethod.PUT, ME + "/preferences", preferencesWithText("저장할 취향", 1))
        .andExpect(status().isOk());
    worker.poll();
    assertThat(data(perform(HttpMethod.GET, ME, null)).at("/preferences/text").asText())
        .isEqualTo("저장할 취향");
    assertThat(
            jdbc.queryForObject("SELECT status FROM search_work WHERE kind='VECTOR'", String.class))
        .isEqualTo("QUEUED");
    assertThat(vectors.vector("user-1")).isNull();
    jdbc.update("UPDATE search_work SET available_at=0 WHERE kind='VECTOR'");
    worker.poll();
    assertThat(vectors.vector("user-1")).containsExactly(preferenceVector());
  }

  @Test
  void preferenceWriteAndEmbeddingJobRollBackTogether() {
    transactions.executeWithoutResult(
        status -> {
          profiles.savePreferences(
              "user-1", new SavePreferencesRequest(List.of("NAIL"), List.of(), null, "롤백할 취향", 1L));
          status.setRollbackOnly();
        });
    assertThat(profiles.getProfile("user-1").preferences().text()).isEmpty();
    assertThat(profiles.getProfile("user-1").version()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM search_work", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM search_user_vector", Integer.class))
        .isZero();
    org.mockito.Mockito.verifyNoInteractions(ai);
  }

  @Test
  void rejectedPreferenceWriteDoesNotQueueEmbedding() throws Exception {
    perform(HttpMethod.PUT, ME + "/preferences", preferencesWithText("오래된 요청", 99))
        .andExpect(status().isConflict());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM search_work", Integer.class)).isZero();
    org.mockito.Mockito.verifyNoInteractions(ai);
  }

  @Test
  void personalizedConsentIsExplicitVersionedAndQueuesExistingVacancyScan() throws Exception {
    perform(HttpMethod.GET, ME + "/notification-preferences", null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success.data.personalizedVacancyEnabled").value(false))
        .andExpect(jsonPath("$.success.data.version").value(1));
    perform(
            HttpMethod.PUT,
            ME + "/notification-preferences",
            "{\"personalizedVacancyEnabled\":true,\"expectedVersion\":1}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success.data.personalizedVacancyEnabled").value(true))
        .andExpect(jsonPath("$.success.data.version").value(2));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM search_work WHERE kind='PERSONALIZE'", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT recommendation_consent_at FROM user_accounts WHERE user_id='user-1'",
                OffsetDateTime.class))
        .isNotNull();
    perform(
            HttpMethod.PUT,
            ME + "/notification-preferences",
            "{\"personalizedVacancyEnabled\":false,\"expectedVersion\":1}")
        .andExpect(status().isConflict());
    perform(
            HttpMethod.PUT,
            ME + "/notification-preferences",
            "{\"personalizedVacancyEnabled\":false,\"expectedVersion\":2}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success.data.personalizedVacancyEnabled").value(false))
        .andExpect(jsonPath("$.success.data.version").value(3));
    assertThat(
            jdbc.queryForObject(
                "SELECT recommendation_consent_at FROM user_accounts WHERE user_id='user-1'",
                OffsetDateTime.class))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM search_work WHERE kind='PERSONALIZE'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void rolledBackLegacySettingCannotLeaveItsValueOrScanJob() {
    transactions.executeWithoutResult(
        status -> {
          profiles.saveNotificationPreferences(
              "user-1", new com.bintime.user.dto.SaveNotificationPreferencesRequest(true, 1L));
          status.setRollbackOnly();
        });
    assertThat(profiles.notificationPreferences("user-1").personalizedVacancyEnabled()).isFalse();
    assertThat(profiles.getProfile("user-1").version()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM search_work", Integer.class)).isZero();
  }

  @Test
  void notificationConsentRequiresSessionCsrfAndValidInput() throws Exception {
    String path = ME + "/notification-preferences";
    mvc.perform(get(path)).andExpect(status().isUnauthorized());
    mvc.perform(
            MockMvcRequestBuilders.put(path)
                .servletPath(path)
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"personalizedVacancyEnabled\":true,\"expectedVersion\":1}"))
        .andExpect(status().isForbidden());
    for (String body :
        List.of(
            "{}",
            "{\"personalizedVacancyEnabled\":null,\"expectedVersion\":1}",
            "{\"personalizedVacancyEnabled\":true,\"expectedVersion\":0}"))
      perform(HttpMethod.PUT, path, body).andExpect(status().isBadRequest());
    assertThat(profiles.notificationPreferences("user-1").personalizedVacancyEnabled()).isFalse();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM search_work", Integer.class)).isZero();
  }

  @Test
  void personalizedCandidatesIncludeNAccountsAndOwnersAcrossAllPages() {
    var access = context.getBean(com.bintime.user.contract.SearchUserAccess.class);
    jdbc.update(
        "update user_accounts set"
            + " role_code='OWNER',recommendation_opt_in='Y',recommendation_consent_at=CURRENT_TIMESTAMP"
            + " where user_id='user-1'");
    for (int i = 0; i < 101; i++)
      jdbc.update(
          "insert into"
              + " user_accounts(user_id,display_name,recommendation_opt_in,recommendation_consent_at)"
              + " values(?,?,'Y',CURRENT_TIMESTAMP)",
          "candidate-%03d".formatted(i),
          "후보");
    var first = access.personalizedCandidates("", 100);
    var second = access.personalizedCandidates(first.getLast(), 100);
    assertThat(first).hasSize(100);
    assertThat(second).hasSize(3).contains("user-1", "user-2");
    assertThat(first).doesNotContainAnyElementsOf(second);
    assertThat(access.personalizedCandidates(second.getLast(), 100)).isEmpty();
    assertThat(access.personalizedCandidateExists("user-1")).isTrue();
    assertThat(access.personalizedCandidateExists("user-2")).isTrue();
    assertThat(access.personalizedCandidateExists("missing-user")).isFalse();
  }

  private JsonNode data(ResultActions result) throws Exception {
    return json.readTree(result.andReturn().getResponse().getContentAsString()).at("/success/data");
  }

  private String preferencesWithText(String text, long expectedVersion) {
    return json.writeValueAsString(
        Map.of(
            "categories",
            List.of("NAIL"),
            "keywords",
            List.of("심플"),
            "maxPrice",
            60000,
            "expectedVersion",
            expectedVersion,
            "text",
            text));
  }

  private String location(double latitude, String observedAt) {
    return """
    {"latitude":%s,"longitude":127.05,"accuracyMeters":30,"source":"DEVICE","observedAt":"%s"}
    """
        .formatted(latitude, observedAt);
  }
}
