package com.bintime.chatting;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bintime.chatting.dto.ChatBookingContextMessage;
import com.bintime.chatting.dto.ChatBookingSuggestionResponse;
import com.bintime.chatting.service.ChatBookingSuggestionService;
import com.bintime.common.web.ApiBusinessException;
import com.bintime.user.security.BintimePrincipal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
    properties = {
      "bintime.auth.client-id=",
      "bintime.auth.client-secret=",
      "bintime.fcm.enabled=false"
    })
@ActiveProfiles("test")
class ChatBookingSuggestionRestTests {
  static final String PATH = "/api/v1/chatting/rooms/room/messages/message/booking-suggestion";
  @Autowired WebApplicationContext context;
  @Autowired JsonMapper json;
  @MockitoBean ChatBookingSuggestionService service;
  MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void requiresRealLoginAndCsrfBeforeAnyModelCall() throws Exception {
    mvc.perform(post(PATH).servletPath(PATH).header("X-Local-User-Id", "owner"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    mvc.perform(post(PATH).servletPath(PATH).session(session()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
    verifyNoInteractions(service);
  }

  @Test
  void noClientTextOrFakeIdentityCanReplaceThePersistedSource() throws Exception {
    var session = session();
    when(service.analyze("owner", "room", "message"))
        .thenReturn(
            new ChatBookingSuggestionResponse(
                "message",
                "내일 17시에 머리자르고 싶어요",
                true,
                "2026-10-18",
                "17:00",
                "커트",
                List.of(
                    new ChatBookingContextMessage(
                        "message",
                        1,
                        "내일 17시에 머리자르고 싶어요",
                        OffsetDateTime.parse("2026-10-17T14:00:00+09:00")))));
    mvc.perform(
            post(PATH)
                .servletPath(PATH)
                .session(session)
                .header("X-CSRF-Token", csrf(session))
                .header("X-Local-User-Id", "outsider")
                .contentType("application/json")
                .content("{\"text\":\"ㅎㅇ\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success.data.sourceMessageId").value("message"))
        .andExpect(jsonPath("$.success.data.sourceContent").value("내일 17시에 머리자르고 싶어요"))
        .andExpect(jsonPath("$.success.data.bookingRequested").value(true))
        .andExpect(jsonPath("$.success.data.contextMessages[0].id").value("message"))
        .andExpect(jsonPath("$.success.data.time").value("17:00"));
    verify(service).analyze("owner", "room", "message");
  }

  @Test
  void newerMessageAndAiFailureReturnDistinctSafeErrors() throws Exception {
    var session = session();
    String csrf = csrf(session);
    when(service.analyze("owner", "room", "message"))
        .thenThrow(new ApiBusinessException(409, "PROPOSAL_SOURCE_OUTDATED"));
    mvc.perform(post(PATH).servletPath(PATH).session(session).header("X-CSRF-Token", csrf))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("PROPOSAL_SOURCE_OUTDATED"));
    doThrow(new ApiBusinessException(503, "CHAT_BOOKING_ANALYSIS_UNAVAILABLE"))
        .when(service)
        .analyze("owner", "room", "message");
    mvc.perform(post(PATH).servletPath(PATH).session(session).header("X-CSRF-Token", csrf))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.retryable").value(true));
  }

  String csrf(MockHttpSession session) throws Exception {
    var response =
        mvc.perform(get("/api/v1/auth/csrf").session(session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    return json.readTree(response.getContentAsString()).at("/success/data/csrfToken").asText();
  }

  static MockHttpSession session() {
    var principal = new BintimePrincipal("owner", "OWNER");
    var session = new MockHttpSession();
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        new SecurityContextImpl(
            new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "kakao")));
    return session;
  }
}
