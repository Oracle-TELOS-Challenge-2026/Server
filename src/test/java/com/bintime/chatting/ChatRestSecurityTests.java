package com.bintime.chatting;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bintime.chatting.dto.*;
import com.bintime.chatting.service.ChatService;
import com.bintime.common.dto.CursorPage;
import com.bintime.common.web.ApiBusinessException;
import com.bintime.user.security.BintimePrincipal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    properties = {
      "bintime.auth.client-id=",
      "bintime.auth.client-secret=",
      "bintime.auth.frontend-url=http://localhost:5173",
      "bintime.auth.cors-enabled=true",
      "bintime.auth.local-enabled=true",
      "bintime.fcm.enabled=false",
      "notification.scheduling-enabled=false"
    })
@ActiveProfiles("test")
class ChatRestSecurityTests {
  private static final String ROOMS = "/api/v1/chatting/rooms";
  private static final String MESSAGES = ROOMS + "/room/messages";
  private static final String READ = ROOMS + "/room/read";
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-16T14:00:00+09:00");
  @Autowired WebApplicationContext context;
  @Autowired JsonMapper json;
  @MockitoBean ChatService service;
  MockMvc mvc;
  MockHttpSession session;
  String csrf;

  @BeforeEach
  void setup() throws Exception {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    var principal = new BintimePrincipal("customer", "CUSTOMER");
    session =
        session(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "kakao"));
    var response =
        mvc.perform(get("/api/v1/auth/csrf").session(session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    csrf = json.readTree(response.getContentAsString()).at("/success/data/csrfToken").asText();
  }

  @Test
  void allEndpointsRequireLoginEvenWithDevelopmentHeader() throws Exception {
    for (var request :
        List.of(
            get(ROOMS),
            get(MESSAGES),
            post(ROOMS).contentType("application/json").content("{\"storeId\":\"shop\"}"),
            post(MESSAGES)
                .contentType("application/json")
                .content("{\"clientMessageId\":\"client\",\"content\":\"안녕하세요\"}"),
            put(READ).contentType("application/json").content("{\"lastReadSequence\":0}"))) {
      mvc.perform(path(request).header("X-Local-User-Id", "customer"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
          .andExpect(jsonPath("$.resultType").value("FAILURE"));
    }
    verifyNoInteractions(service);
  }

  @Test
  void authenticatedPrincipalFromAnotherSchemeCannotEnterChat() throws Exception {
    var other = session(new UsernamePasswordAuthenticationToken("customer", "ignored", List.of()));
    mvc.perform(get(ROOMS).servletPath(ROOMS).session(other))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    verifyNoInteractions(service);
  }

  @Test
  void allChangesRequireTheCsrfTokenIssuedForThisSession() throws Exception {
    for (String token : List.of("", "another-session-token")) {
      for (var request :
          List.of(
              post(ROOMS).contentType("application/json").content("{\"storeId\":\"shop\"}"),
              post(MESSAGES)
                  .contentType("application/json")
                  .content("{\"clientMessageId\":\"client\",\"content\":\"hello\"}"),
              put(READ).contentType("application/json").content("{\"lastReadSequence\":0}"))) {
        mvc.perform(path(request).session(session).header("X-CSRF-Token", token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
      }
    }
    verifyNoInteractions(service);
  }

  @Test
  void createsAndReusesRoomsWithSessionIdentityAndSharedEnvelope() throws Exception {
    var room = room();
    when(service.create(eq("customer"), eq("create-key"), eq(new CreateChatRoomRequest("shop"))))
        .thenReturn(new ChatService.Result<>(room, 201), new ChatService.Result<>(room, 200));
    for (int expected : new int[] {201, 200}) {
      mvc.perform(
              change(post(ROOMS), "create-key")
                  .header("X-Local-User-Id", "someone-else")
                  .content("{\"storeId\":\"shop\"}"))
          .andExpect(status().is(expected))
          .andExpect(jsonPath("$.success.data.id").value("room"))
          .andExpect(jsonPath("$.error").value(nullValue()))
          .andExpect(jsonPath("$.meta.path").value(ROOMS));
    }
    verify(service, times(2)).create("customer", "create-key", new CreateChatRoomRequest("shop"));
  }

  @Test
  void roomAndHistoryQueriesPassPagingAndUseTheirDefaults() throws Exception {
    when(service.list(anyString(), nullable(String.class), anyInt()))
        .thenReturn(new ChatRoomListResponse(List.of(room()), new CursorPage(null, false)));
    when(service.history(anyString(), anyString(), nullable(Long.class), anyInt()))
        .thenReturn(
            new ChatMessageListResponse(
                List.of(message()), new ChatMessageListResponse.Page(false, null)));
    mvc.perform(get(ROOMS).servletPath(ROOMS).session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success.data.items[0].id").value("room"));
    mvc.perform(
            get(ROOMS)
                .servletPath(ROOMS)
                .session(session)
                .param("cursor", "cursor")
                .param("limit", "7"))
        .andExpect(status().isOk());
    mvc.perform(get(MESSAGES).servletPath(MESSAGES).session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success.data.items[0].sequence").value(1));
    mvc.perform(
            get(MESSAGES)
                .servletPath(MESSAGES)
                .session(session)
                .param("beforeSequence", "42")
                .param("limit", "9"))
        .andExpect(status().isOk());
    verify(service).list("customer", null, 20);
    verify(service).list("customer", "cursor", 7);
    verify(service).history("customer", "room", null, 30);
    verify(service).history("customer", "room", 42L, 9);
  }

  @Test
  void messageSendPreservesNewAndDuplicateStatusesAndTextContract() throws Exception {
    var request = new SendChatMessageRequest("client", "안녕하세요");
    when(service.send("customer", "room", "send-key", request))
        .thenReturn(
            new ChatService.Result<>(message(), 201), new ChatService.Result<>(message(), 200));
    for (int expected : new int[] {201, 200}) {
      mvc.perform(
              change(post(MESSAGES), "send-key")
                  .content("{\"clientMessageId\":\"client\",\"content\":\"안녕하세요\"}"))
          .andExpect(status().is(expected))
          .andExpect(jsonPath("$.success.data.type").value("TEXT"))
          .andExpect(jsonPath("$.success.data.clientMessageId").value("client"));
    }
    verify(service, times(2)).send("customer", "room", "send-key", request);
  }

  @Test
  void readPositionPassesTheRequestedSequenceAndBusinessErrorsUseSharedEnvelope() throws Exception {
    when(service.read("customer", "room", 1L)).thenReturn(new ChatReadResponse("room", 1L, NOW));
    mvc.perform(change(put(READ), null).content("{\"lastReadSequence\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success.data.lastReadSequence").value(1));
    when(service.history("customer", "room", null, 30))
        .thenThrow(new ApiBusinessException(404, "ROOM_NOT_FOUND"));
    mvc.perform(get(MESSAGES).servletPath(MESSAGES).session(session))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("ROOM_NOT_FOUND"));
    verify(service).read("customer", "room", 1L);
  }

  @Test
  void requiredIdempotencyHeaderAndMalformedPagingFailBeforeDispatch() throws Exception {
    mvc.perform(change(post(ROOMS), null).content("{\"storeId\":\"shop\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            change(post(MESSAGES), null)
                .content("{\"clientMessageId\":\"client\",\"content\":\"hello\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get(MESSAGES).servletPath(MESSAGES).session(session).param("beforeSequence", "invalid"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  void onlyTheFrontendOriginCanPreflightCookieChatRequests() throws Exception {
    mvc.perform(
            options(MESSAGES)
                .servletPath(MESSAGES)
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header(
                    "Access-Control-Request-Headers", "content-type,x-csrf-token,idempotency-key"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
        .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    mvc.perform(
            options(MESSAGES)
                .servletPath(MESSAGES)
                .header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  private MockHttpServletRequestBuilder change(MockHttpServletRequestBuilder request, String key) {
    path(request).session(session).header("X-CSRF-Token", csrf).contentType("application/json");
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  private static MockHttpServletRequestBuilder path(MockHttpServletRequestBuilder request) {
    return request.with(
        r -> {
          r.setServletPath(r.getRequestURI());
          return r;
        });
  }

  private static MockHttpSession session(Authentication authentication) {
    var session = new MockHttpSession();
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        new SecurityContextImpl(authentication));
    return session;
  }

  private static ChatRoomResponse room() {
    return new ChatRoomResponse(
        "room",
        new ChatRoomResponse.Store("shop", "매장", null),
        new ChatRoomResponse.Customer("customer", "고객"),
        "OPEN",
        null,
        0,
        0L,
        NOW);
  }

  private static ChatMessageResponse message() {
    return new ChatMessageResponse(
        "message",
        "room",
        1L,
        new ChatMessageResponse.Sender("customer", "CUSTOMER", "고객"),
        "client",
        "TEXT",
        "안녕하세요",
        NOW);
  }
}
