package com.bintime.chatting.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(
    description =
        "C-WS JSON 프레임. /api/v1/chatting/ws, subprotocol=bintime.chat.v1. HTTP Controller가 아닌 별도 전송"
            + " 계약")
public record ChatWebSocketFrame(
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {
              "connection.authenticate",
              "connection.ready",
              "room.subscribe",
              "room.subscribed",
              "room.resync_required",
              "room.unsubscribe",
              "room.unsubscribed",
              "message.send",
              "message.ack",
              "message.created",
              "room.read",
              "room.read.ack",
              "room.read.updated",
              "ping",
              "pong",
              "error"
            })
        String type,
    @Schema(description = "클라이언트 명령·응답 연결용 ID, 최대 64자. 서버 이벤트에서는 생략 가능", nullable = true)
        String requestId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Payload payload) {

  @Schema(
      name = "ChatWebSocketPayload",
      oneOf = {
        Authenticate.class,
        Ready.class,
        Subscribe.class,
        Subscribed.class,
        ResyncRequired.class,
        Room.class,
        SendMessage.class,
        Message.class,
        Read.class,
        ReadAck.class,
        ReadUpdated.class,
        Ping.class,
        Pong.class,
        Error.class
      })
  public sealed interface Payload
      permits Authenticate,
          Ready,
          Subscribe,
          Subscribed,
          ResyncRequired,
          Room,
          SendMessage,
          Message,
          Read,
          ReadAck,
          ReadUpdated,
          Ping,
          Pong,
          Error {}

  @Schema(name = "ChatAuthenticatePayload", description = "connection.authenticate: 연결 후 5초 이내 전송")
  public record Authenticate(String csrfToken) implements Payload {}

  @Schema(name = "ChatReadyPayload", description = "connection.ready")
  public record Ready(String userId, Integer heartbeatSeconds) implements Payload {}

  @Schema(name = "ChatSubscribePayload", description = "room.subscribe: afterSequence 초과 메시지부터 복구")
  public record Subscribe(String roomId, Long afterSequence) implements Payload {}

  @Schema(name = "ChatSubscribedPayload", description = "room.subscribed: 이력 재생 경계")
  public record Subscribed(String roomId, Long replayThroughSequence) implements Payload {}

  @Schema(
      name = "ChatResyncRequiredPayload",
      description = "room.resync_required: 누락 1000건 초과 시 REST 이력 조회")
  public record ResyncRequired(String roomId, Long latestSequence) implements Payload {}

  @Schema(name = "ChatRoomPayload", description = "room.unsubscribe / room.unsubscribed")
  public record Room(String roomId) implements Payload {}

  @Schema(
      name = "ChatSendMessagePayload",
      description = "message.send: REST와 동일한 clientMessageId 유지")
  public record SendMessage(String roomId, String clientMessageId, String content)
      implements Payload {}

  @Schema(
      name = "ChatMessagePayload",
      description = "message.ack / message.created. ACK는 영속 저장만 의미")
  public record Message(ChatMessageResponse message) implements Payload {}

  @Schema(name = "ChatReadPayload", description = "room.read")
  public record Read(String roomId, Long lastReadSequence) implements Payload {}

  @Schema(name = "ChatReadAckPayload", description = "room.read.ack")
  public record ReadAck(String roomId, Long lastReadSequence, OffsetDateTime readAt)
      implements Payload {}

  @Schema(name = "ChatReadUpdatedPayload", description = "room.read.updated")
  public record ReadUpdated(
      String roomId, String userId, Long lastReadSequence, OffsetDateTime readAt)
      implements Payload {}

  @Schema(name = "ChatPingPayload", description = "ping: 빈 payload")
  public record Ping() implements Payload {}

  @Schema(name = "ChatPongPayload", description = "pong")
  public record Pong(OffsetDateTime serverTime) implements Payload {}

  @Schema(name = "ChatErrorPayload", description = "error: retryAfterSeconds는 요청 제한 시 선택 제공")
  public record Error(
      String code,
      String message,
      Boolean retryable,
      @Schema(nullable = true) Integer retryAfterSeconds)
      implements Payload {}
}
