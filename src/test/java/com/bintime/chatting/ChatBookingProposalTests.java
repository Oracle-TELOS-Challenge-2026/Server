package com.bintime.chatting;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.bintime.chatting.dto.*;
import com.bintime.chatting.repository.ChatRepository;
import com.bintime.chatting.service.ChatService;
import com.bintime.common.web.ApiBusinessException;
import com.bintime.store.contract.StoreReservationPort;
import com.zaxxer.hikari.HikariDataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

class ChatBookingProposalTests {
  private HikariDataSource source;
  private JdbcTemplate db;
  private ChatRepository repository;
  private ChatService chats;
  private StoreReservationPort reservations;
  private TransactionTemplate tx;
  private MutableClock clock;
  private final JsonMapper json = JsonMapper.builder().build();
  private final List<Object> committed = new CopyOnWriteArrayList<>();
  private final ApplicationEventPublisher publisher =
      event -> {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void afterCommit() {
                committed.add(event);
              }
            });
      };

  @BeforeEach
  void setup() {
    source = new HikariDataSource();
    source.setJdbcUrl(
        "jdbc:h2:mem:chat-proposal-" + UUID.randomUUID() + ";MODE=Oracle;LOCK_TIMEOUT=10000");
    source.setUsername("sa");
    source.setMaximumPoolSize(16);
    new ResourceDatabasePopulator(new ClassPathResource("chatting-schema.sql")).execute(source);
    db = new JdbcTemplate(source);
    // A port write using the same DataSource must roll back together with the chat transition.
    db.execute(
        "create table test_confirmed_reservations (reservation_id varchar(64) primary key,"
            + " proposal_id varchar(64) unique)");
    for (String user : List.of("customer", "customer2", "outsider"))
      db.update("insert into user_accounts values(?,?, 'CUSTOMER')", user, user + " 이름");
    for (String user : List.of("owner", "owner2"))
      db.update("insert into user_accounts values(?,?, 'OWNER')", user, user + " 이름");
    db.update("insert into store_shops values('shop','owner','빈틈 네일',null)");
    db.update("insert into store_shops values('shop2','owner2','다른 매장',null)");
    repository = new ChatRepository(db);
    tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    clock = new MutableClock(Instant.parse("2026-10-16T10:00:00.123456Z"));
    reservations = mock(StoreReservationPort.class);
    when(reservations.reserveProposal(any())).thenAnswer(call -> reserve(call.getArgument(0)));
    chats = new ChatService(repository, tx, clock, json, publisher, reservations);
    committed.clear();
  }

  @AfterEach
  void close() {
    source.close();
  }

  @Test
  void creationPersistsTheOwnersFinalSnapshotWithoutReservingAndRestoresItInHistory() {
    String room = room();
    var sourceMessage = customerMessage(room, "source");
    var request = request(sourceMessage.id());
    committed.clear();
    var result = chats.propose("owner", room, "proposal", request);
    assertThat(result.status()).isEqualTo(201);
    var card = result.data();
    var proposal = card.bookingProposal();
    assertThat(card.type()).isEqualTo("BOOKING_PROPOSAL");
    assertThat(card.sender().id()).isEqualTo("owner");
    assertThat(card.sequence()).isEqualTo(sourceMessage.sequence() + 1);
    assertThat(proposal.sourceMessageId()).isEqualTo(sourceMessage.id());
    assertThat(proposal.serviceId()).isEqualTo("service-gel");
    assertThat(proposal.serviceDescription()).isEqualTo("젤 네일 + 기존 젤 제거 + 아트 2개");
    assertThat(proposal.startAt()).isEqualTo(request.startAt());
    assertThat(proposal.endAt()).isEqualTo(request.startAt().plusMinutes(95));
    assertThat(proposal.durationMinutes()).isEqualTo(95);
    assertThat(proposal.price()).isEqualTo(85000);
    assertThat(proposal.waitlistId()).isEqualTo("wait-1");
    assertThat(proposal.status()).isEqualTo("PENDING");
    assertThat(proposal.version()).isEqualTo(1);
    assertThat(proposal.reservationId()).isNull();
    assertThat(proposal(room, proposal.id())).isEqualTo(proposal);
    assertThat(chats.list("customer", null, 20).items().getFirst().lastMessage()).isEqualTo(card);
    assertThat(chats.replay("customer", room, sourceMessage.sequence(), card.sequence(), 20))
        .containsExactly(card);
    var command = ArgumentCaptor.forClass(StoreReservationPort.ProposalCommand.class);
    verify(reservations).validateProposal(command.capture());
    assertStoredCommand(command.getValue(), proposal);
    verify(reservations, never()).reserveProposal(any());
    assertThat(count("test_confirmed_reservations")).isZero();
    assertThat(committed)
        .filteredOn(ChatService.MessageCommitted.class::isInstance)
        .extracting(event -> ((ChatService.MessageCommitted) event).message())
        .containsExactly(card);
    assertThat(committed)
        .filteredOn(ChatService.PushRequested.class::isInstance)
        .containsExactly(new ChatService.PushRequested(card, "customer"));
  }

  @Test
  void onlyCurrentStoreOwnerCanProposeAndOnlyTheRoomCustomerCanConfirm() {
    String room = room();
    var sourceMessage = customerMessage(room, "source");
    var request = request(sourceMessage.id());
    failure(403, "OWNER_REQUIRED", () -> chats.propose("customer", room, "customer", request));
    failure(404, "ROOM_NOT_FOUND", () -> chats.propose("outsider", room, "outsider", request));
    failure(404, "ROOM_NOT_FOUND", () -> chats.propose("owner2", room, "owner2", request));
    var proposal = chats.propose("owner", room, "valid", request).data().bookingProposal();
    failure(
        403,
        "CUSTOMER_REQUIRED",
        () ->
            chats.confirm(
                "owner", room, proposal.id(), "owner", new ConfirmBookingProposalRequest(1L)));
    failure(
        404,
        "ROOM_NOT_FOUND",
        () ->
            chats.confirm(
                "customer2", room, proposal.id(), "other", new ConfirmBookingProposalRequest(1L)));
    verify(reservations, never()).reserveProposal(any());
    db.update("update store_shops set owner_user_id='owner2' where store_id='shop'");
    failure(404, "ROOM_NOT_FOUND", () -> chats.propose("owner", room, "old-owner", request));
    assertThat(chats.propose("owner2", room, "new-owner", request).status()).isEqualTo(201);
  }

  @Test
  void sourceMustBeACustomerTextInThisRoomAndTheLatestCustomerRequest() {
    String room = room();
    var older = customerMessage(room, "old");
    var latest = customerMessage(room, "latest");
    var owner = chats.send("owner", room, new SendChatMessageRequest("owner", "네 가능합니다"));
    String otherRoom =
        chats.create("customer2", "other-room", new CreateChatRoomRequest("shop")).data().id();
    var otherSource =
        chats.send("customer2", otherRoom, new SendChatMessageRequest("other", "다른 고객의 요청"));
    failure(
        404,
        "SOURCE_MESSAGE_NOT_FOUND",
        () -> chats.propose("owner", room, "missing", request("missing")));
    failure(
        404,
        "SOURCE_MESSAGE_NOT_FOUND",
        () -> chats.propose("owner", room, "owner-source", request(owner.id())));
    failure(
        404,
        "SOURCE_MESSAGE_NOT_FOUND",
        () -> chats.propose("owner", room, "foreign-source", request(otherSource.id())));
    failure(
        409,
        "PROPOSAL_SOURCE_OUTDATED",
        () -> chats.propose("owner", room, "old-source", request(older.id())));
    var proposal =
        chats
            .propose("owner", room, "latest-source", request(latest.id()))
            .data()
            .bookingProposal();
    assertThat(proposal.sourceMessageId()).isEqualTo(latest.id());
    assertThat(count("chatting_booking_proposals")).isEqualTo(1);
    verify(reservations, times(1)).validateProposal(any());
  }

  @Test
  void replacingAProposalMakesTheOldCardUnconfirmableAndReplaysTheOriginalCreationSnapshot() {
    String room = room();
    var sourceMessage = customerMessage(room, "source");
    var first = chats.propose("owner", room, "first", request(sourceMessage.id()));
    committed.clear();
    var second = chats.propose("owner", room, "second", request(sourceMessage.id()));
    var old = proposal(room, first.data().bookingProposal().id());
    assertThat(old.status()).isEqualTo("SUPERSEDED");
    assertThat(old.version()).isEqualTo(2);
    assertThat(second.data().bookingProposal().status()).isEqualTo("PENDING");
    assertThat(second.data().bookingProposal().version()).isEqualTo(1);
    assertThat(committed)
        .filteredOn(ChatService.MessageUpdated.class::isInstance)
        .extracting(event -> ((ChatService.MessageUpdated) event).message().bookingProposal())
        .containsExactly(old);
    failure(
        409,
        "PROPOSAL_SUPERSEDED",
        () ->
            chats.confirm(
                "customer", room, old.id(), "old-confirm", new ConfirmBookingProposalRequest(1L)));
    int events = committed.size();
    assertThat(chats.propose("owner", room, "first", request(sourceMessage.id()))).isEqualTo(first);
    assertThat(committed).hasSize(events);
    assertThat(count("chatting_booking_proposals")).isEqualTo(2);
    verify(reservations, never()).reserveProposal(any());
  }

  @Test
  void onlyANewCustomerMessageInvalidatesAPendingProposalAndItCanThenBeReplaced() {
    String room = room();
    var sourceMessage = customerMessage(room, "source");
    var first =
        chats.propose("owner", room, "first", request(sourceMessage.id())).data().bookingProposal();
    chats.send("owner", room, new SendChatMessageRequest("owner-reply", "제안을 확인해 주세요"));
    assertThat(customerMessage(room, "source")).isEqualTo(sourceMessage);
    assertThat(proposal(room, first.id()).status()).isEqualTo("PENDING");
    assertThat(proposal(room, first.id()).version()).isEqualTo(1);
    committed.clear();
    var changed = customerMessage(room, "changed");
    var review = proposal(room, first.id());
    assertThat(review.status()).isEqualTo("REVIEW_REQUIRED");
    assertThat(review.version()).isEqualTo(2);
    assertThat(committed)
        .filteredOn(ChatService.MessageUpdated.class::isInstance)
        .extracting(event -> ((ChatService.MessageUpdated) event).message().bookingProposal())
        .containsExactly(review);
    failure(
        409,
        "PROPOSAL_REVIEW_REQUIRED",
        () ->
            chats.confirm(
                "customer",
                room,
                first.id(),
                "review-confirm",
                new ConfirmBookingProposalRequest(2L)));
    var replacement =
        chats.propose("owner", room, "replacement", request(changed.id())).data().bookingProposal();
    assertThat(replacement.status()).isEqualTo("PENDING");
    assertThat(proposal(room, first.id()).status()).isEqualTo("SUPERSEDED");
    assertThat(proposal(room, first.id()).version()).isEqualTo(3);
    verify(reservations, never()).reserveProposal(any());
  }

  @Test
  void confirmUsesOnlyTheSavedSnapshotAndPublishesAnUpdateAfterTheReservationCommits() {
    String room = room();
    var sourceMessage = customerMessage(room, "source");
    var card = chats.propose("owner", room, "create", request(sourceMessage.id())).data();
    var proposal = card.bookingProposal();
    committed.clear();
    var result =
        chats.confirm(
            "customer", room, proposal.id(), "confirm", new ConfirmBookingProposalRequest(1L));
    assertThat(result.status()).isEqualTo(200);
    assertThat(result.data().id()).isEqualTo(card.id());
    assertThat(result.data().sequence()).isEqualTo(card.sequence());
    var confirmed = result.data().bookingProposal();
    assertThat(confirmed.status()).isEqualTo("CONFIRMED");
    assertThat(confirmed.version()).isEqualTo(2);
    assertThat(confirmed.reservationId()).isEqualTo("reservation-" + proposal.id());
    assertThat(proposal(room, proposal.id())).isEqualTo(confirmed);
    assertThat(chats.list("customer", null, 20).items().getFirst().lastMessage())
        .isEqualTo(result.data());
    var command = ArgumentCaptor.forClass(StoreReservationPort.ProposalCommand.class);
    verify(reservations).reserveProposal(command.capture());
    assertStoredCommand(command.getValue(), proposal);
    assertThat(count("test_confirmed_reservations")).isEqualTo(1);
    assertThat(committed)
        .filteredOn(ChatService.MessageUpdated.class::isInstance)
        .extracting(event -> ((ChatService.MessageUpdated) event).message())
        .containsExactly(result.data());
    assertThat(committed).noneMatch(ChatService.MessageCommitted.class::isInstance);
    assertThat(committed).noneMatch(ChatService.PushRequested.class::isInstance);
    int events = committed.size();
    assertThat(
            chats.confirm(
                "customer", room, proposal.id(), "confirm", new ConfirmBookingProposalRequest(1L)))
        .isEqualTo(result);
    assertThat(committed).hasSize(events);
    verify(reservations, times(1)).reserveProposal(any());
    customerMessage(room, "after-confirm");
    assertThat(proposal(room, proposal.id())).isEqualTo(confirmed);
  }

  @Test
  void versionMissingProposalAndAlreadyConfirmedFailuresAreDistinctAndDoNotReserveAgain() {
    String room = room();
    var sourceMessage = customerMessage(room, "source");
    var proposal =
        chats
            .propose("owner", room, "create", request(sourceMessage.id()))
            .data()
            .bookingProposal();
    failure(
        404,
        "PROPOSAL_NOT_FOUND",
        () ->
            chats.confirm(
                "customer", room, "missing", "missing", new ConfirmBookingProposalRequest(1L)));
    String otherRoom =
        chats.create("customer", "other-room", new CreateChatRoomRequest("shop2")).data().id();
    var otherSource = customerMessage(otherRoom, "other-source");
    var otherProposal =
        chats
            .propose("owner2", otherRoom, "other-proposal", request(otherSource.id()))
            .data()
            .bookingProposal();
    failure(
        404,
        "PROPOSAL_NOT_FOUND",
        () ->
            chats.confirm(
                "customer",
                room,
                otherProposal.id(),
                "foreign-proposal",
                new ConfirmBookingProposalRequest(1L)));
    failure(
        409,
        "PROPOSAL_VERSION_CONFLICT",
        () ->
            chats.confirm(
                "customer", room, proposal.id(), "version", new ConfirmBookingProposalRequest(2L)));
    verify(reservations, never()).reserveProposal(any());
    chats.confirm(
        "customer", room, proposal.id(), "confirm", new ConfirmBookingProposalRequest(1L));
    failure(
        409,
        "PROPOSAL_ALREADY_CONFIRMED",
        () ->
            chats.confirm(
                "customer", room, proposal.id(), "again", new ConfirmBookingProposalRequest(1L)));
    verify(reservations, times(1)).reserveProposal(any());
  }

  @Test
  void reusingAnIdempotencyKeyWithChangedProposalOrConfirmBodyIsRejected() {
    String room = room();
    var sourceMessage = customerMessage(room, "source");
    var request = request(sourceMessage.id());
    var proposal = chats.propose("owner", room, "create", request).data().bookingProposal();
    var changed =
        new CreateBookingProposalRequest(
            request.sourceMessageId(),
            request.serviceId(),
            request.serviceDescription(),
            request.startAt(),
            request.durationMinutes(),
            request.price() + 1,
            request.waitlistId());
    failure(409, "IDEMPOTENCY_KEY_REUSED", () -> chats.propose("owner", room, "create", changed));
    chats.confirm(
        "customer", room, proposal.id(), "confirm", new ConfirmBookingProposalRequest(1L));
    failure(
        409,
        "IDEMPOTENCY_KEY_REUSED",
        () ->
            chats.confirm(
                "customer", room, proposal.id(), "confirm", new ConfirmBookingProposalRequest(2L)));
    assertThat(count("chatting_booking_proposals")).isEqualTo(1);
    assertThat(count("test_confirmed_reservations")).isEqualTo(1);
  }

  @Test
  void aStoreConflictRollsBackBothReservationAndProposalAndAllowsRetryAfterTheConflictClears() {
    String room = room();
    var sourceMessage = customerMessage(room, "source");
    var proposal =
        chats
            .propose("owner", room, "create", request(sourceMessage.id()))
            .data()
            .bookingProposal();
    doAnswer(
            call -> {
              reserve(call.getArgument(0));
              throw new ApiBusinessException(409, "RESERVATION_TIME_CONFLICT");
            })
        .when(reservations)
        .reserveProposal(any());
    committed.clear();
    failure(
        409,
        "RESERVATION_TIME_CONFLICT",
        () ->
            chats.confirm(
                "customer", room, proposal.id(), "confirm", new ConfirmBookingProposalRequest(1L)));
    assertThat(count("test_confirmed_reservations")).isZero();
    assertThat(proposal(room, proposal.id())).isEqualTo(proposal);
    assertThat(committed).isEmpty();
    doAnswer(call -> reserve(call.getArgument(0))).when(reservations).reserveProposal(any());
    assertThat(
            chats
                .confirm(
                    "customer",
                    room,
                    proposal.id(),
                    "confirm",
                    new ConfirmBookingProposalRequest(1L))
                .data()
                .bookingProposal()
                .status())
        .isEqualTo("CONFIRMED");
    assertThat(count("test_confirmed_reservations")).isEqualTo(1);
  }

  @Test
  void failureSavingTheConfirmationReplayRollsBackReservationStateAndEvents() {
    String room = room();
    var sourceMessage = customerMessage(room, "source");
    var proposal =
        chats
            .propose("owner", room, "create", request(sourceMessage.id()))
            .data()
            .bookingProposal();
    var broken =
        new ChatRepository(db) {
          @Override
          public void saveReplay(
              String user,
              String operation,
              String key,
              String hash,
              String body,
              int status,
              OffsetDateTime expires) {
            throw new IllegalStateException("replay persistence failed");
          }
        };
    var brokenChats = new ChatService(broken, tx, clock, json, publisher, reservations);
    committed.clear();
    assertThatThrownBy(
            () ->
                brokenChats.confirm(
                    "customer",
                    room,
                    proposal.id(),
                    "broken",
                    new ConfirmBookingProposalRequest(1L)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count("test_confirmed_reservations")).isZero();
    assertThat(proposal(room, proposal.id())).isEqualTo(proposal);
    assertThat(committed).isEmpty();
  }

  @Test
  void failedCreationValidationLeavesExistingProposalPendingWithoutInsertingACard() {
    String room = room();
    var sourceMessage = customerMessage(room, "source");
    var proposal =
        chats
            .propose("owner", room, "create", request(sourceMessage.id()))
            .data()
            .bookingProposal();
    doThrow(new ApiBusinessException(409, "SERVICE_NOT_AVAILABLE"))
        .when(reservations)
        .validateProposal(any());
    committed.clear();
    failure(
        409,
        "SERVICE_NOT_AVAILABLE",
        () -> chats.propose("owner", room, "failed", request(sourceMessage.id())));
    assertThat(proposal(room, proposal.id())).isEqualTo(proposal);
    assertThat(count("chatting_booking_proposals")).isEqualTo(1);
    assertThat(count("chatting_messages")).isEqualTo(2);
    assertThat(count("test_confirmed_reservations")).isZero();
    assertThat(committed).isEmpty();
  }

  @Test
  void concurrentRetriesCreateOneCardAndOneReservationWithTheSameReplayResult() throws Exception {
    String room = room();
    var sourceMessage = customerMessage(room, "source");
    var created =
        concurrent(8, index -> chats.propose("owner", room, "create", request(sourceMessage.id())));
    assertThat(created).allSatisfy(result -> assertThat(result).isEqualTo(created.getFirst()));
    assertThat(count("chatting_booking_proposals")).isEqualTo(1);
    var proposal = created.getFirst().data().bookingProposal();
    committed.clear();
    var confirmed =
        concurrent(
            8,
            index ->
                chats.confirm(
                    "customer",
                    room,
                    proposal.id(),
                    "confirm",
                    new ConfirmBookingProposalRequest(1L)));
    assertThat(confirmed).allSatisfy(result -> assertThat(result).isEqualTo(confirmed.getFirst()));
    assertThat(count("test_confirmed_reservations")).isEqualTo(1);
    verify(reservations, times(1)).validateProposal(any());
    verify(reservations, times(1)).reserveProposal(any());
    assertThat(committed).filteredOn(ChatService.MessageUpdated.class::isInstance).hasSize(1);
  }

  @Test
  void concurrentConfirmationWithDifferentKeysCanOnlyCreateOneReservation() throws Exception {
    String room = room();
    var sourceMessage = customerMessage(room, "source");
    var proposal =
        chats
            .propose("owner", room, "create", request(sourceMessage.id()))
            .data()
            .bookingProposal();
    var results =
        concurrent(
            6,
            index -> {
              try {
                return chats.confirm(
                    "customer",
                    room,
                    proposal.id(),
                    "confirm-" + index,
                    new ConfirmBookingProposalRequest(1L));
              } catch (ApiBusinessException failure) {
                return failure;
              }
            });
    assertThat(results).filteredOn(ChatService.Result.class::isInstance).hasSize(1);
    assertThat(results)
        .filteredOn(ApiBusinessException.class::isInstance)
        .hasSize(5)
        .allSatisfy(
            result -> {
              var failure = (ApiBusinessException) result;
              assertThat(failure.status).isEqualTo(409);
              assertThat(failure.code).isEqualTo("PROPOSAL_ALREADY_CONFIRMED");
            });
    assertThat(count("test_confirmed_reservations")).isEqualTo(1);
    verify(reservations, times(1)).reserveProposal(any());
  }

  @Test
  void racingACustomerChangeWithProposalCreationNeverLeavesAConfirmableOldRequest()
      throws Exception {
    String room = room();
    var sourceMessage = customerMessage(room, "source");
    var results =
        concurrent(
            2,
            index -> {
              if (index == 0) return customerMessage(room, "change-during-create");
              try {
                return chats.propose("owner", room, "create", request(sourceMessage.id()));
              } catch (ApiBusinessException failure) {
                assertThat(failure.code).isEqualTo("PROPOSAL_SOURCE_OUTDATED");
                return failure;
              }
            });
    assertThat(results).hasSize(2);
    var cards =
        chats.history("customer", room, null, 100).items().stream()
            .filter(message -> "BOOKING_PROPOSAL".equals(message.type()))
            .toList();
    assertThat(cards)
        .allSatisfy(
            card -> {
              assertThat(card.bookingProposal().status()).isEqualTo("REVIEW_REQUIRED");
              failure(
                  409,
                  "PROPOSAL_REVIEW_REQUIRED",
                  () ->
                      chats.confirm(
                          "customer",
                          room,
                          card.bookingProposal().id(),
                          "confirm-raced",
                          new ConfirmBookingProposalRequest(card.bookingProposal().version())));
            });
    verify(reservations, never()).reserveProposal(any());
  }

  private void assertStoredCommand(
      StoreReservationPort.ProposalCommand command, ChatBookingProposalResponse proposal) {
    assertThat(command.userId()).isEqualTo("customer");
    assertThat(command.ownerId()).isEqualTo("owner");
    assertThat(command.storeId()).isEqualTo("shop");
    assertThat(command.proposalId()).isEqualTo(proposal.id());
    assertThat(command.idempotencyKey()).isNotBlank();
    assertThat(command.serviceId()).isEqualTo(proposal.serviceId());
    assertThat(command.serviceDescription()).isEqualTo(proposal.serviceDescription());
    assertThat(command.startAt()).isEqualTo(proposal.startAt());
    assertThat(command.durationMinutes()).isEqualTo(proposal.durationMinutes());
    assertThat(command.price()).isEqualTo(proposal.price());
    assertThat(command.waitlistId()).isEqualTo(proposal.waitlistId());
  }

  private StoreReservationPort.ConfirmedReservation reserve(
      StoreReservationPort.ProposalCommand command) {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    String reservationId = "reservation-" + command.proposalId();
    db.update(
        "insert into test_confirmed_reservations values(?, ?)",
        reservationId,
        command.proposalId());
    return new StoreReservationPort.ConfirmedReservation(
        reservationId,
        command.storeId(),
        command.serviceId(),
        "slot-1",
        "CONFIRMED",
        command.startAt(),
        command.startAt().plusMinutes(command.durationMinutes()),
        command.price(),
        command.price(),
        command.waitlistId(),
        1L);
  }

  private String room() {
    return chats.create("customer", "room", new CreateChatRoomRequest("shop")).data().id();
  }

  private ChatMessageResponse customerMessage(String room, String clientId) {
    return chats.send("customer", room, new SendChatMessageRequest(clientId, "예약 문의 " + clientId));
  }

  private CreateBookingProposalRequest request(String messageId) {
    return new CreateBookingProposalRequest(
        messageId,
        "service-gel",
        "젤 네일 + 기존 젤 제거 + 아트 2개",
        OffsetDateTime.parse("2026-10-20T14:00:00+09:00"),
        95,
        85000L,
        "wait-1");
  }

  private ChatBookingProposalResponse proposal(String room, String proposalId) {
    return chats.history("customer", room, null, 100).items().stream()
        .map(ChatMessageResponse::bookingProposal)
        .filter(Objects::nonNull)
        .filter(proposal -> proposal.id().equals(proposalId))
        .findFirst()
        .orElseThrow();
  }

  private long count(String table) {
    return db.queryForObject("select count(*) from " + table, Long.class);
  }

  private static void failure(int status, String code, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            ApiBusinessException.class,
            failure -> {
              assertThat(failure.status).isEqualTo(status);
              assertThat(failure.code).isEqualTo(code);
            });
  }

  private static <T> List<T> concurrent(int count, IntFunction<T> action) throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var start = new CountDownLatch(1);
      var futures = new ArrayList<Future<T>>();
      for (int index = 0; index < count; index++) {
        int task = index;
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  return action.apply(task);
                }));
      }
      start.countDown();
      var results = new ArrayList<T>();
      for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
      return results;
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> instant;

    MutableClock(Instant instant) {
      this.instant = new AtomicReference<>(instant);
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
      return instant.get();
    }
  }
}
