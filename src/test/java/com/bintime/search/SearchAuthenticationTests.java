package com.bintime.search;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bintime.search.dto.*;
import com.bintime.store.contract.SearchStoreCatalog;
import com.bintime.user.security.BintimePrincipal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "bintime.auth.local-enabled=false"})
@ActiveProfiles("test")
class SearchAuthenticationTests {
  @Autowired WebApplicationContext context;
  @Autowired JdbcTemplate db;
  @Autowired JsonMapper json;
  @MockitoBean SearchStoreCatalog catalog;
  MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    for (String table :
        List.of("search_offer", "search_intent", "search_idempotency", "search_work"))
      db.update("delete from " + table);
    db.update("delete from user_accounts");
    for (String id : List.of("alice", "bob", "owner"))
      db.update(
          "insert into user_accounts(user_id,display_name,role_code) values(?,?,?)",
          id,
          id,
          id.equals("owner") ? "OWNER" : "CUSTOMER");
    when(catalog.describe(any(), any())).thenReturn(new SearchStoreCatalog.References(null, null));
  }

  private MockHttpSession session(String id) {
    // 세션에 저장된 역할은 일부러 원장과 다르게 두어 현재 DB 역할을 검증한다.
    var principal = new BintimePrincipal(id, "OWNER");
    var session = new MockHttpSession();
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        new SecurityContextImpl(
            new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "kakao")));
    return session;
  }

  private String csrf(MockHttpSession session) throws Exception {
    var response =
        mvc.perform(get("/api/v1/auth/csrf").servletPath("/api/v1/auth/csrf").session(session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    return json.readTree(response.getContentAsString()).at("/success/data/csrfToken").asText();
  }

  private WaitlistConditions conditions() {
    var start = OffsetDateTime.now().plusDays(1).withNano(0);
    return new WaitlistConditions(
        null,
        "NAIL",
        null,
        start,
        start.plusHours(3),
        new WaitlistConditions.Location(37.51335413975466, 126.92649449142719),
        3000,
        60000L,
        List.of());
  }

  @Test
  void customerSessionCanCreateReadUpdateCancelAndCannotReadAnotherCustomersWaitlist()
      throws Exception {
    var alice = session("alice");
    String token = csrf(alice);
    String body = json.writeValueAsString(new CreateWaitlistRequest("MANUAL", conditions(), true));
    var response =
        mvc.perform(
                post("/api/v1/search/waitlists")
                    .session(alice)
                    .header("X-CSRF-Token", token)
                    .header("Idempotency-Key", "create")
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse();
    String id = json.readTree(response.getContentAsString()).at("/success/data/id").asText();
    mvc.perform(get("/api/v1/search/waitlists/me").session(alice)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/search/waitlists/" + id).session(alice)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/search/waitlists/" + id).session(session("bob")))
        .andExpect(status().isNotFound());
    mvc.perform(
            patch("/api/v1/search/waitlists/" + id)
                .session(alice)
                .header("X-CSRF-Token", token)
                .contentType("application/json")
                .content(
                    json.writeValueAsString(new UpdateWaitlistRequest(conditions(), 1L, true))))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/search/waitlists/" + id + "/cancel")
                .session(alice)
                .header("X-CSRF-Token", token)
                .header("Idempotency-Key", "cancel")
                .contentType("application/json")
                .content("{\"expectedVersion\":2}"))
        .andExpect(status().isOk());
  }

  @Test
  void anonymousOwnerDeletedAccountAndForgedLocalHeadersCannotAccessCustomerEndpoints()
      throws Exception {
    mvc.perform(get("/api/v1/search/waitlists/me")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/search/waitlists/me").header("X-Local-User-Id", "alice"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/search/waitlists/me").session(session("owner")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("CUSTOMER_REQUIRED"));
    var owner = session("owner");
    mvc.perform(
            post("/api/v1/search/intents/analyze")
                .session(owner)
                .header("X-CSRF-Token", csrf(owner))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("CUSTOMER_REQUIRED"));
    mvc.perform(get("/api/v1/search/waitlists/me").session(session("deleted")))
        .andExpect(status().isUnauthorized());
    db.update("update user_accounts set role_code='OWNER' where user_id='alice'");
    mvc.perform(get("/api/v1/search/waitlists/me").session(session("alice")))
        .andExpect(status().isForbidden());
  }

  @Test
  void cookieWritesRequireCsrfAndInternalEndpointsStillRequireInternalToken() throws Exception {
    var alice = session("alice");
    for (String path : List.of("/api/v1/search/waitlists", "/api/v1/search/intents/analyze")) {
      mvc.perform(post(path).session(alice).contentType("application/json").content("{}"))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
      mvc.perform(
              post(path)
                  .session(alice)
                  .header("X-CSRF-Token", "forged")
                  .contentType("application/json")
                  .content("{}"))
          .andExpect(status().isForbidden());
    }
    mvc.perform(
            post("/api/v1/search/intents/analyze")
                .session(alice)
                .header("X-CSRF-Token", csrf(alice))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/internal/search/match-jobs")
                .session(alice)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("INTERNAL_AUTH_REQUIRED"));
    // 공개 검색은 인증을 요구하지 않는다. 필수 입력 누락은 400으로 응답한다.
    mvc.perform(get("/api/v1/search/stores")).andExpect(status().isBadRequest());
  }
}
