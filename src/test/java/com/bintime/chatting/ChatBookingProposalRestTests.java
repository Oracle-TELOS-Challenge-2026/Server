package com.bintime.chatting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bintime.chatting.dto.*;
import com.bintime.chatting.service.ChatService;
import com.bintime.common.web.ApiBusinessException;
import com.bintime.user.security.BintimePrincipal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
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
class ChatBookingProposalRestTests {
  private static final String CREATE = "/api/v1/chatting/rooms/room/booking-proposals";
  private static final String CONFIRM = CREATE + "/proposal/confirm";
  private static final OffsetDateTime START = OffsetDateTime.parse("2026-10-20T14:00:00+09:00");
  private static final String CREATE_JSON =
      """
      {"sourceMessageId":"source","serviceId":"service","serviceDescription":"젤+제거",
       "startAt":"2026-10-20T14:00:00+09:00","durationMinutes":90,"price":75000,
       "waitlistId":"waitlist"}
      """;
  @Autowired WebApplicationContext context;
  @Autowired JsonMapper json;
  @MockitoBean ChatService service;
  MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void bothEndpointsRequireLoginAndRejectDevelopmentIdentityHeaders() throws Exception {
    for (String path : List.of(CREATE, CONFIRM)) {
      mvc.perform(
              post(path)
                  .servletPath(path)
                  .header("X-Local-User-Id", "owner")
                  .header("Idempotency-Key", "key")
                  .contentType("application/json")
                  .content("{}"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }
    verifyNoInteractions(service);
  }

  @Test
  void bothEndpointsRequireTheCurrentSessionsCsrfToken() throws Exception {
    var session = session("owner", "OWNER");
    for (String path : List.of(CREATE, CONFIRM)) {
      for (String csrf : List.of("", "wrong-token")) {
        mvc.perform(
                post(path)
                    .servletPath(path)
                    .session(session)
                    .header("X-CSRF-Token", csrf)
                    .header("Idempotency-Key", "key")
                    .contentType("application/json")
                    .content("{}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
      }
    }
    verifyNoInteractions(service);
  }

  @Test
  void bothEndpointsRequireIdempotencyHeaderBeforeServiceDispatch() throws Exception {
    var session = session("owner", "OWNER");
    mvc.perform(change(CREATE, session).content(CREATE_JSON)).andExpect(status().isBadRequest());
    mvc.perform(change(CONFIRM, session).content("{\"expectedVersion\":1}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  void createPassesOnlySessionIdentityAndTheFullOwnerSnapshot() throws Exception {
    var request =
        new CreateBookingProposalRequest(
            "source", "service", "젤+제거", START, 90, 75000L, "waitlist");
    when(service.propose(
            eq("owner"), eq("room"), eq("key"), any(CreateBookingProposalRequest.class)))
        .thenReturn(new ChatService.Result<>(card("PENDING", 1L, null), 201));
    mvc.perform(
            change(CREATE, session("owner", "OWNER"))
                .header("Idempotency-Key", "key")
                .header("X-Local-User-Id", "other")
                .content(CREATE_JSON))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success.data.type").value("BOOKING_PROPOSAL"))
        .andExpect(jsonPath("$.success.data.bookingProposal.serviceDescription").value("젤+제거"))
        .andExpect(jsonPath("$.success.data.bookingProposal.durationMinutes").value(90))
        .andExpect(jsonPath("$.success.data.bookingProposal.price").value(75000))
        .andExpect(jsonPath("$.success.data.bookingProposal.status").value("PENDING"))
        .andExpect(jsonPath("$.meta.path").value(CREATE));
    var captured = org.mockito.ArgumentCaptor.forClass(CreateBookingProposalRequest.class);
    verify(service).propose(eq("owner"), eq("room"), eq("key"), captured.capture());
    assertThat(captured.getValue())
        .usingRecursiveComparison()
        .ignoringFields("startAt")
        .isEqualTo(request);
    assertThat(captured.getValue().startAt().toInstant()).isEqualTo(START.toInstant());
  }

  @Test
  void confirmPassesVersionAndReturnsTheExistingCardWithItsReservation() throws Exception {
    when(service.confirm(
            "customer", "room", "proposal", "key", new ConfirmBookingProposalRequest(1L)))
        .thenReturn(new ChatService.Result<>(card("CONFIRMED", 2L, "reservation"), 200));
    mvc.perform(
            change(CONFIRM, session("customer", "CUSTOMER"))
                .header("Idempotency-Key", "key")
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success.data.id").value("card"))
        .andExpect(jsonPath("$.success.data.bookingProposal.status").value("CONFIRMED"))
        .andExpect(jsonPath("$.success.data.bookingProposal.version").value(2))
        .andExpect(jsonPath("$.success.data.bookingProposal.reservationId").value("reservation"));
    verify(service)
        .confirm("customer", "room", "proposal", "key", new ConfirmBookingProposalRequest(1L));
  }

  @Test
  void staleProposalErrorsKeepTheirMachineReadableCode() throws Exception {
    when(service.confirm(
            "customer", "room", "proposal", "key", new ConfirmBookingProposalRequest(1L)))
        .thenThrow(new ApiBusinessException(409, "PROPOSAL_REVIEW_REQUIRED"));
    mvc.perform(
            change(CONFIRM, session("customer", "CUSTOMER"))
                .header("Idempotency-Key", "key")
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("PROPOSAL_REVIEW_REQUIRED"));
  }

  private MockHttpServletRequestBuilder change(String path, MockHttpSession session)
      throws Exception {
    var response =
        mvc.perform(get("/api/v1/auth/csrf").session(session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    String csrf =
        json.readTree(response.getContentAsString()).at("/success/data/csrfToken").asText();
    return post(path)
        .servletPath(path)
        .session(session)
        .header("X-CSRF-Token", csrf)
        .contentType("application/json");
  }

  private static MockHttpSession session(String user, String role) {
    var principal = new BintimePrincipal(user, role);
    var session = new MockHttpSession();
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        new SecurityContextImpl(
            new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "kakao")));
    return session;
  }

  private static ChatMessageResponse card(String status, Long version, String reservationId) {
    return new ChatMessageResponse(
        "card",
        "room",
        2L,
        new ChatMessageResponse.Sender("owner", "OWNER", "사장님"),
        "proposal",
        "BOOKING_PROPOSAL",
        "젤+제거",
        START.minusDays(1),
        new ChatBookingProposalResponse(
            "proposal",
            "source",
            "service",
            "젤+제거",
            START,
            START.plusMinutes(90),
            90,
            75000L,
            status,
            version,
            reservationId,
            "waitlist"));
  }
}
