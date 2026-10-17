package com.bintime.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bintime.user.security.BintimePrincipal;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:store-auth;DB_CLOSE_DELAY=-1",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "bintime.auth.local-enabled=false",
      "bintime.auth.store-jwt.enabled=true",
      "bintime.auth.store-jwt.issuer=https://issuer.bintime.test",
      "bintime.auth.store-jwt.audience=bintime-store"
    })
@ActiveProfiles("test")
class StoreAuthenticationTests {
  private static final RSAKey KEY;
  private static final HttpServer JWK_SERVER;

  static {
    try {
      KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
      JWK_SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      JWK_SERVER.createContext(
          "/jwks",
          exchange -> {
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
              output.write(body);
            }
          });
      JWK_SERVER.start();
    } catch (Exception error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    properties.add(
        "bintime.auth.store-jwt.jwk-set-uri",
        () -> "http://127.0.0.1:" + JWK_SERVER.getAddress().getPort() + "/jwks");
  }

  @AfterAll
  static void stopJwks() {
    JWK_SERVER.stop(0);
  }

  @Autowired WebApplicationContext context;
  @Autowired JdbcTemplate db;
  @Autowired JsonMapper json;
  MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    db.update("delete from store_shops");
    db.update("delete from user_accounts");
    db.update(
        "insert into user_accounts(user_id,display_name,role_code)"
            + " values('alice','고객','CUSTOMER')");
    db.update(
        "insert into user_accounts(user_id,display_name,role_code) values('owner','사장','OWNER')");
  }

  private MockHttpSession session(String id) {
    var principal = new BintimePrincipal(id, "OWNER");
    var session = new MockHttpSession();
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        new SecurityContextImpl(
            new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "kakao")));
    return session;
  }

  private String token(String subject, String issuer, String audience, Instant expires, RSAKey key)
      throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .subject(subject)
            .issuer(issuer)
            .audience(audience)
            .issueTime(Date.from(Instant.now().minusSeconds(10)))
            .expirationTime(expires == null ? null : Date.from(expires))
            .claim("role", "OWNER")
            .build();
    var jwt =
        new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  private String token(String id) throws Exception {
    return token(
        id, "https://issuer.bintime.test", "bintime-store", Instant.now().plusSeconds(300), KEY);
  }

  @Test
  void publicQueriesNeedNoLoginAndProtectedQueriesRejectAnonymous() throws Exception {
    mvc.perform(get("/api/v1/stores/categories")).andExpect(status().isOk());
    for (String path :
        List.of(
            "/api/v1/stores/missing",
            "/api/v1/stores/missing/services",
            "/api/v1/stores/missing/timetable?date=2026-10-20",
            "/api/v1/stores/missing/slots?from=2026-10-17&to=2026-10-18",
            "/api/v1/stores/missing/slots/missing")) {
      int status = mvc.perform(get(path)).andReturn().getResponse().getStatus();
      assertThat(status).isNotIn(401, 403);
    }
    mvc.perform(get("/api/v1/reservations/me?scope=UPCOMING")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/owner/stores/missing/timetable").param("date", "2026-10-17"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/reservations/me?scope=UPCOMING").header("X-Local-User-Id", "alice"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void cookieSessionReadsReservationsAndCookieWritesRequireRealCsrf() throws Exception {
    var session = session("alice");
    mvc.perform(get("/api/v1/reservations/me?scope=UPCOMING").session(session))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/reservations")
                .session(session)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
    var response =
        mvc.perform(get("/api/v1/auth/csrf").servletPath("/api/v1/auth/csrf").session(session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    String csrf =
        json.readTree(response.getContentAsString()).at("/success/data/csrfToken").asText();
    mvc.perform(
            post("/api/v1/reservations")
                .session(session)
                .header("X-CSRF-Token", "wrong")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/reservations")
                .session(session)
                .header("X-CSRF-Token", csrf)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void customerStoreRegistrationRequiresCsrfAndOwnerAccessWorksWithoutRelogin() throws Exception {
    var session = session("alice");
    var customer = new BintimePrincipal("alice", "CUSTOMER");
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        new SecurityContextImpl(
            new OAuth2AuthenticationToken(customer, customer.getAuthorities(), "kakao")));
    String body =
        json.writeValueAsString(
            java.util.Map.of(
                "name",
                "첫 매장",
                "category",
                "NAIL",
                "address",
                "서울",
                "location",
                java.util.Map.of("latitude", 37.5, "longitude", 127.0),
                "openingHours",
                List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN").stream()
                    .map(day -> java.util.Map.of("dayOfWeek", day, "closed", true))
                    .toList()));
    mvc.perform(
            post("/api/v1/owner/stores")
                .session(session)
                .header("Idempotency-Key", "first-store")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
    assertThat(
            db.queryForObject(
                "select role_code from user_accounts where user_id='alice'", String.class))
        .isEqualTo("CUSTOMER");
    var csrfResponse =
        mvc.perform(get("/api/v1/auth/csrf").servletPath("/api/v1/auth/csrf").session(session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    String csrf =
        json.readTree(csrfResponse.getContentAsString()).at("/success/data/csrfToken").asText();
    var response =
        mvc.perform(
                post("/api/v1/owner/stores")
                    .session(session)
                    .header("Idempotency-Key", "first-store")
                    .header("X-CSRF-Token", csrf)
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse();
    String store = json.readTree(response.getContentAsString()).at("/success/data/id").asText();
    assertThat(
            db.queryForObject(
                "select role_code from user_accounts where user_id='alice'", String.class))
        .isEqualTo("OWNER");
    mvc.perform(
            get("/api/v1/owner/stores/" + store + "/timetable")
                .param("date", "2026-10-17")
                .session(session))
        .andExpect(status().isOk());
  }

  @Test
  void bearerUsesDatabaseRoleAndDoesNotPersistAuthenticationInSession() throws Exception {
    var response =
        mvc.perform(
                get("/api/v1/reservations/me?scope=UPCOMING")
                    .header("Authorization", "Bearer " + token("alice")))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(response.getRequest().getSession(false)).isNull();
    mvc.perform(
            get("/api/v1/owner/stores/missing/timetable")
                .param("date", "2026-10-17")
                .header("Authorization", "Bearer " + token("alice")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/reservations")
                .header("Authorization", "Bearer " + token("alice"))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/reservations/me?scope=UPCOMING")).andExpect(status().isUnauthorized());
  }

  @Test
  void ownerSessionCannotReadAnotherOwnersStore() throws Exception {
    db.update(
        "insert into"
            + " store_shops(store_id,owner_user_id,name,category_code,address,latitude,longitude,opening_hours_json,timezone,rating_average,rating_count,version_no,created_at)"
            + " values('shop','owner','매장','NAIL','주소',37,127,'[]','Asia/Seoul',0,0,1,CURRENT_TIMESTAMP)");
    mvc.perform(
            get("/api/v1/owner/stores/shop/timetable")
                .param("date", "2026-10-17")
                .session(session("alice")))
        .andExpect(status().isForbidden());
    db.update("update user_accounts set role_code='OWNER' where user_id='alice'");
    mvc.perform(
            get("/api/v1/owner/stores/shop/timetable")
                .param("date", "2026-10-17")
                .session(session("alice")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("STORE_FORBIDDEN"));
    mvc.perform(
            get("/api/v1/owner/stores/shop/timetable")
                .param("date", "2026-10-17")
                .session(session("owner")))
        .andExpect(status().isOk());
  }

  @Test
  void searchSharesBearerValidationAndAllowsOwnersToUseCustomerEndpoints() throws Exception {
    String path = "/api/v1/search/waitlists/me";
    mvc.perform(get(path).header("Authorization", "Bearer " + token("alice")))
        .andExpect(status().isOk());
    mvc.perform(get(path).header("Authorization", "Bearer " + token("owner")))
        .andExpect(status().isOk());
    mvc.perform(get(path)).andExpect(status().isUnauthorized());
    mvc.perform(get(path).session(session("alice")).header("Authorization", "Bearer broken"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/search/waitlists")
                .header("Authorization", "Bearer " + token("alice"))
                .header("Idempotency-Key", "search-jwt")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void invalidTokensCannotFallBackToAValidCookie() throws Exception {
    mvc.perform(
            post("/api/v1/reservations")
                .session(session("alice"))
                .header("Authorization", "Bearer broken")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isUnauthorized());
    for (String token :
        List.of(
            "broken",
            token("alice", "https://issuer.bintime.test", "bintime-store", null, KEY),
            token("alice", "wrong", "bintime-store", Instant.now().plusSeconds(300), KEY),
            token(
                "alice",
                "https://issuer.bintime.test",
                "wrong",
                Instant.now().plusSeconds(300),
                KEY),
            token(
                "alice",
                "https://issuer.bintime.test",
                "bintime-store",
                Instant.now().minusSeconds(120),
                KEY),
            token(
                "alice",
                "https://issuer.bintime.test",
                "bintime-store",
                Instant.now().plusSeconds(300),
                new RSAKeyGenerator(2048).generate()))) {
      mvc.perform(
              get("/api/v1/reservations/me?scope=UPCOMING")
                  .session(session("alice"))
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isUnauthorized());
    }
    mvc.perform(
            get("/api/v1/reservations/me?scope=UPCOMING")
                .header("Authorization", "Bearer " + token("deleted")))
        .andExpect(status().isUnauthorized());
  }
}
