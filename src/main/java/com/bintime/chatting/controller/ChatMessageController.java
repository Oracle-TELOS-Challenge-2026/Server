package com.bintime.chatting.controller;

import com.bintime.chatting.dto.ChatMessageListResponse;
import com.bintime.chatting.dto.ChatMessageResponse;
import com.bintime.chatting.dto.SendChatMessageRequest;
import com.bintime.chatting.service.ChatService;
import com.bintime.user.security.BintimePrincipal;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "chatting")
@ApiResponse(responseCode = "400", description = "입력 형식 오류 또는 필수 헤더 없음", content = @Content)
@ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content)
@ApiResponse(responseCode = "403", description = "권한 부족 또는 CSRF 토큰 없음·불일치", content = @Content)
@ApiResponse(responseCode = "404", description = "매장 또는 참여 채팅방 없음", content = @Content)
public class ChatMessageController {

  private final ChatService service;

  public ChatMessageController(@Lazy ChatService service) {
    this.service = service;
  }

  @Operation(
      operationId = "getMessages",
      summary = "C-03 · 채팅 메시지 이력 조회",
      description =
          "beforeSequence보다 작은 최근 메시지를 화면용 오름차순으로 반환. limit 기본 30, 최대 100.\n\n"
              + "- 로그인한 채팅방 참여자 (고객/소유 매장 사장님)",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481ab99f8e9575674c9d5"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(
      responseCode = "200",
      description = "처리 성공",
      useReturnTypeSchema = true,
      content =
          @Content(
              mediaType = "application/json",
              examples =
                  @ExampleObject(
                      name = "success",
                      value =
                          """
                          {
                            "resultType": "SUCCESS",
                            "success": {
                              "data": {
                                "items": [
                                  {
                                    "id": "msg_001",
                                    "roomId": "room_001",
                                    "sequence": 1,
                                    "sender": {
                                      "id": "usr_001",
                                      "role": "CUSTOMER",
                                      "displayName": "빈틈고객"
                                    },
                                    "clientMessageId": "550e8400-e29b-41d4-a716-446655440000",
                                    "type": "TEXT",
                                    "content": "내일 오후 4시 예약 가능한가요?",
                                    "createdAt": "2026-10-16T14:10:00+09:00"
                                  }
                                ],
                                "page": {
                                  "hasOlder": false,
                                  "nextBeforeSequence": null
                                }
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/chatting/rooms/{roomId}/messages"
                            }
                          }
                          """)))
  @GetMapping(
      value = "/api/v1/chatting/rooms/{roomId}/messages",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<ChatMessageListResponse>> getMessages(
      @AuthenticationPrincipal BintimePrincipal principal,
      @PathVariable("roomId") String roomId,
      @io.swagger.v3.oas.annotations.Parameter(
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      minimum = "1",
                      maximum = "9007199254740991"))
          @RequestParam(value = "beforeSequence", required = false)
          Long beforeSequence,
      @io.swagger.v3.oas.annotations.Parameter(
              schema = @io.swagger.v3.oas.annotations.media.Schema(minimum = "1", maximum = "100"))
          @RequestParam(value = "limit", required = false, defaultValue = "30")
          Integer limit) {
    return ResponseEntity.ok(
        com.bintime.common.dto.ApiResponse.success(
            service.history(userId(principal), roomId, beforeSequence, limit)));
  }

  @Operation(
      operationId = "sendMessage",
      summary = "C-04 · 채팅 메시지 전송 (REST)",
      description =
          "Idempotency-Key 필수. WS와 같은 저장·중복 제거 경로. clientMessageId 동일+내용 다름은 409. 내용 1\\~2000자.\n\n"
              + "- 로그인한 채팅방 참여자 (고객/소유 매장 사장님)\n"
              + "- X-CSRF-Token 필수\n"
              + "- Idempotency-Key 필수 (24시간 재생)",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd4818aa794f4d31bbcd63a"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(
      responseCode = "201",
      description = "처리 성공",
      useReturnTypeSchema = true,
      content =
          @Content(
              mediaType = "application/json",
              examples =
                  @ExampleObject(
                      name = "success",
                      value =
                          """
                          {
                            "resultType": "SUCCESS",
                            "success": {
                              "data": {
                                "id": "msg_001",
                                "roomId": "room_001",
                                "sequence": 1,
                                "sender": {
                                  "id": "usr_001",
                                  "role": "CUSTOMER",
                                  "displayName": "빈틈고객"
                                },
                                "clientMessageId": "550e8400-e29b-41d4-a716-446655440000",
                                "type": "TEXT",
                                "content": "내일 오후 4시 예약 가능한가요?",
                                "createdAt": "2026-10-16T14:10:00+09:00"
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/chatting/rooms/{roomId}/messages"
                            }
                          }
                          """)))
  @ApiResponse(
      responseCode = "200",
      description = "기존 리소스 또는 동일 요청 재사용",
      useReturnTypeSchema = true,
      content =
          @Content(
              mediaType = "application/json",
              examples =
                  @ExampleObject(
                      name = "success",
                      value =
                          """
                          {
                            "resultType": "SUCCESS",
                            "success": {
                              "data": {
                                "id": "msg_001",
                                "roomId": "room_001",
                                "sequence": 1,
                                "sender": {
                                  "id": "usr_001",
                                  "role": "CUSTOMER",
                                  "displayName": "빈틈고객"
                                },
                                "clientMessageId": "550e8400-e29b-41d4-a716-446655440000",
                                "type": "TEXT",
                                "content": "내일 오후 4시 예약 가능한가요?",
                                "createdAt": "2026-10-16T14:10:00+09:00"
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/chatting/rooms/{roomId}/messages"
                            }
                          }
                          """)))
  @ApiResponse(
      responseCode = "409",
      description = "동일 clientMessageId·Idempotency-Key에 다른 내용 또는 읽기 전용 채팅방",
      content = @Content)
  @ApiResponse(responseCode = "429", description = "사용자별 초당 5건의 신규 메시지 한도 초과", content = @Content)
  @PostMapping(
      value = "/api/v1/chatting/rooms/{roomId}/messages",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<ChatMessageResponse>> sendMessage(
      @AuthenticationPrincipal BintimePrincipal principal,
      @PathVariable("roomId") String roomId,
      @io.swagger.v3.oas.annotations.Parameter(
              description = "현재 로그인 세션의 CSRF 토큰. 누락·불일치는 403 CSRF_INVALID",
              required = true)
          @RequestHeader(value = "X-CSRF-Token", required = false)
          String csrfToken,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody SendChatMessageRequest request) {
    var result = service.send(userId(principal), roomId, idempotencyKey, request);
    return ResponseEntity.status(result.status())
        .body(com.bintime.common.dto.ApiResponse.success(result.data()));
  }

  private static String userId(BintimePrincipal principal) {
    com.bintime.common.web.ApiBusinessException.require(principal != null, 401, "UNAUTHORIZED");
    return principal.userId();
  }
}
