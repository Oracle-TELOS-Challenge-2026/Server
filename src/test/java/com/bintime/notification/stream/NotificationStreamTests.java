package com.bintime.notification.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bintime.common.web.ApiExceptionHandler;
import com.bintime.notification.repository.NotificationRepository;
import com.bintime.notification.repository.NotificationRepository.Event;
import com.bintime.user.security.BintimePrincipal;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class NotificationStreamTests {
  private static final BintimePrincipal ALICE = new BintimePrincipal("alice", "CUSTOMER");
  private static final String PATH = "/api/v1/notifications/stream";
  private final NotificationRepository db = mock(NotificationRepository.class);
  private final List<Event> events = new CopyOnWriteArrayList<>();
  private final MutableClock clock = new MutableClock();
  private NotificationStream stream;
  private MockMvc mvc;
  private MockHttpSession session;

  @BeforeEach
  void setUp() {
    when(db.latestSequence("alice"))
        .thenAnswer(invocation -> events.stream().mapToLong(Event::sequence).max().orElse(0));
    when(db.event(eq("alice"), anyString()))
        .thenAnswer(
            invocation ->
                events.stream()
                    .filter(event -> event.id().equals(invocation.getArgument(1)))
                    .findFirst()
                    .orElse(null));
    when(db.events(eq("alice"), anyLong(), any()))
        .thenAnswer(invocation -> page(invocation.getArgument(1), invocation.getArgument(2)));
    stream = new NotificationStream(db, clock);
    mvc =
        MockMvcBuilders.standaloneSetup(new StreamController(stream))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    session = new MockHttpSession();
    session.setMaxInactiveInterval(0);
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        new SecurityContextImpl(
            new OAuth2AuthenticationToken(ALICE, ALICE.getAuthorities(), "kakao")));
  }

  @AfterEach
  void tearDown() {
    stream.shutdown();
  }

  @Test
  void lastEventIdTakesPrecedenceAndFramesContainRawJson() throws Exception {
    events.addAll(List.of(event(1), event(2), event(3)));
    var result =
        mvc.perform(
                get(PATH)
                    .session(session)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .header("Last-Event-ID", "event-1")
                    .param("afterEventId", "event-2"))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andReturn();
    pollUntil(() -> body(result).contains("id:event-3"));

    assertThat(body(result))
        .contains(
            "event:notification.created\n",
            "id:event-2\n",
            "data:{\"notificationId\":\"noti-2\"}\n\n")
        .doesNotContain("id:event-1\n", "resultType", "success", "meta");
    verify(db).event("alice", "event-1");
    verify(db, never()).event("alice", "event-2");
  }

  @Test
  void invalidLastEventIdDoesNotFallBackToValidQueryCursor() throws Exception {
    events.add(event(1));
    mvc.perform(
            get(PATH)
                .session(session)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header("Last-Event-ID", "invalid-header")
                .param("afterEventId", "event-1"))
        .andExpect(status().isBadRequest())
        .andExpect(request().asyncNotStarted())
        .andExpect(jsonPath("$.error.code").value("INVALID_STREAM_CURSOR"));
    verify(db, never()).event("alice", "event-1");
  }

  @Test
  void cursorOlderThanTwentyFourHoursFailsBeforeConnection() throws Exception {
    events.add(event(1, OffsetDateTime.now(clock).minusHours(24).minusNanos(1)));
    mvc.perform(
            get(PATH)
                .session(session)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .param("afterEventId", "event-1"))
        .andExpect(status().isGone())
        .andExpect(request().asyncNotStarted())
        .andExpect(jsonPath("$.error.code").value("STREAM_CURSOR_EXPIRED"));
  }

  @Test
  void foreignCursorIsRejectedWithoutReadingTheOtherUsersEvents() throws Exception {
    when(db.event("bob", "foreign-event")).thenReturn(event(9));
    mvc.perform(
            get(PATH)
                .session(session)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .param("afterEventId", "foreign-event"))
        .andExpect(status().isBadRequest())
        .andExpect(request().asyncNotStarted())
        .andExpect(jsonPath("$.error.code").value("INVALID_STREAM_CURSOR"));
    verify(db).event("alice", "foreign-event");
    verify(db, never()).event("bob", "foreign-event");
  }

  @Test
  void replayAndNewLiveEventsAreDeliveredOnce() throws Exception {
    events.addAll(List.of(event(1), event(2)));
    var result = open("event-1");
    pollUntil(() -> body(result).contains("id:event-2"));
    events.add(event(3));
    pollUntil(() -> body(result).contains("id:event-3"));
    stream.poll();
    stream.poll();

    assertThat(StringUtils.countOccurrencesOf(body(result), "id:event-2\n")).isEqualTo(1);
    assertThat(StringUtils.countOccurrencesOf(body(result), "id:event-3\n")).isEqualTo(1);
    assertThat(body(result).indexOf("id:event-2\n"))
        .isLessThan(body(result).indexOf("id:event-3\n"));
  }

  @Test
  void replayCutoffIsFrozenAcrossMoreThanOnePage() throws Exception {
    var nearExpiry = OffsetDateTime.now(clock).minusHours(24).plusSeconds(1);
    for (int i = 1; i <= 102; i++) events.add(event(i, nearExpiry));
    AtomicBoolean secondPageAllowed = new AtomicBoolean(false);
    when(db.events(eq("alice"), anyLong(), any()))
        .thenAnswer(
            invocation -> {
              long after = invocation.getArgument(1);
              if (after >= 101 && !secondPageAllowed.get()) return List.of();
              return page(after, invocation.getArgument(2));
            });
    var result = open("event-1");
    pollUntil(() -> body(result).contains("id:event-101\n"));
    clock.advance(Duration.ofSeconds(2));
    secondPageAllowed.set(true);
    pollUntil(() -> body(result).contains("id:event-102\n"));

    assertThat(StringUtils.countOccurrencesOf(body(result), "event:notification.created\n"))
        .isEqualTo(101);
    assertThat(body(result)).doesNotContain("id:event-1\n");
    assertThat(StringUtils.countOccurrencesOf(body(result), "id:event-102\n")).isEqualTo(1);
  }

  @Test
  void sessionInvalidationSendsExpiryAndClosesTheConnection() throws Exception {
    var result = open(null);
    session.invalidate();
    pollUntil(() -> body(result).contains("event:session.expired\n"));
    result.getAsyncResult(2000);
    mvc.perform(asyncDispatch(result)).andExpect(status().isOk());

    assertThat(body(result))
        .contains("data:{\"reason\":\"SESSION_EXPIRED\"}")
        .doesNotContain("resultType");
    assertThat(StringUtils.countOccurrencesOf(body(result), "event:session.expired\n"))
        .isEqualTo(1);
  }

  @Test
  void liveConnectionSkipsEarlierEventsAndUsesCommentHeartbeats() throws Exception {
    events.add(event(1));
    var result = open(null);
    clock.advance(Duration.ofSeconds(15));
    pollUntil(() -> body(result).contains(":heartbeat"));
    assertThat(body(result))
        .contains("retry:3000", ":connected", ":heartbeat")
        .doesNotContain("id:event-1", "resultType");
  }

  private MvcResult open(String cursor) throws Exception {
    var request = get(PATH).session(session).accept(MediaType.TEXT_EVENT_STREAM);
    if (cursor != null) request.param("afterEventId", cursor);
    return mvc.perform(request)
        .andExpect(status().isOk())
        .andExpect(request().asyncStarted())
        .andReturn();
  }

  private List<Event> page(long after, OffsetDateTime since) {
    return events.stream()
        .filter(event -> event.sequence() > after && !event.createdAt().isBefore(since))
        .limit(100)
        .toList();
  }

  private Event event(long sequence) {
    return event(sequence, OffsetDateTime.now(clock));
  }

  private Event event(long sequence, OffsetDateTime createdAt) {
    return new Event(
        sequence,
        "event-" + sequence,
        "notification.created",
        "{\"notificationId\":\"noti-" + sequence + "\"}",
        createdAt);
  }

  private String body(MvcResult result) {
    return new String(
        result.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
  }

  private void pollUntil(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      stream.poll();
      Thread.sleep(10);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  @RestController
  static class StreamController {
    private final NotificationStream stream;

    StreamController(NotificationStream stream) {
      this.stream = stream;
    }

    @GetMapping(value = PATH, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter open(
        HttpSession session,
        @RequestHeader(value = "Last-Event-ID", required = false) String last,
        @RequestParam(value = "afterEventId", required = false) String after) {
      return stream.open(ALICE, session, last, after);
    }
  }

  private static class MutableClock extends Clock {
    private volatile Instant now = Instant.parse("2026-10-16T12:00:00Z");

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
