package com.bintime.chatting.controller;

import com.bintime.chatting.dto.ChatReadResponse;
import com.bintime.chatting.dto.ChatRoomListResponse;
import com.bintime.chatting.dto.ChatRoomResponse;
import com.bintime.chatting.dto.CreateChatRoomRequest;
import com.bintime.chatting.dto.UpdateChatReadRequest;
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
import org.springframework.web.bind.annotation.PutMapping;
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
public class ChatRoomController {

  private final ChatService service;

  public ChatRoomController(@Lazy ChatService service) {
    this.service = service;
  }

  @Operation(
      operationId = "createChatRoom",
      summary = "C-01 · 매장 문의 채팅방 생성·조회",
      description =
          "고객+매장당 방 1개. 신규 201, 기존 200. Idempotency-Key 필수.\n\n"
              + "- CUSTOMER 또는 OWNER — 본인 데이터\n"
              + "- X-CSRF-Token 필수\n"
              + "- Idempotency-Key 필수 (24시간 재생)",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd4813abd64ce628e66ba48"))
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
                                "id": "room_001",
                                "store": {
                                  "id": "shop_moment",
                                  "name": "네일 모먼트",
                                  "imageUrl": null
                                },
                                "customer": {
                                  "id": "usr_001",
                                  "displayName": "빈틈고객"
                                },
                                "status": "OPEN",
                                "lastMessage": null,
                                "unreadCount": 0,
                                "lastReadSequence": 0,
                                "createdAt": "2026-10-16T14:00:00+09:00"
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/chatting/rooms"
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
                                "id": "room_001",
                                "store": {
                                  "id": "shop_moment",
                                  "name": "네일 모먼트",
                                  "imageUrl": null
                                },
                                "customer": {
                                  "id": "usr_001",
                                  "displayName": "빈틈고객"
                                },
                                "status": "OPEN",
                                "lastMessage": null,
                                "unreadCount": 0,
                                "lastReadSequence": 0,
                                "createdAt": "2026-10-16T14:00:00+09:00"
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/chatting/rooms"
                            }
                          }
                          """)))
  @ApiResponse(
      responseCode = "409",
      description = "동일 Idempotency-Key에 다른 요청 본문",
      content = @Content)
  @PostMapping(
      value = "/api/v1/chatting/rooms",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<ChatRoomResponse>> createChatRoom(
      @AuthenticationPrincipal BintimePrincipal principal,
      @io.swagger.v3.oas.annotations.Parameter(
              description = "현재 로그인 세션의 CSRF 토큰. 누락·불일치는 403 CSRF_INVALID",
              required = true)
          @RequestHeader(value = "X-CSRF-Token", required = false)
          String csrfToken,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody CreateChatRoomRequest request) {
    var result = service.create(userId(principal), idempotencyKey, request);
    return ResponseEntity.status(result.status())
        .body(com.bintime.common.dto.ApiResponse.success(result.data()));
  }

  @Operation(
      operationId = "getChatRooms",
      summary = "C-02 · 참여 채팅방 목록 조회",
      description = "고객은 본인, 사장님은 소유 매장 방만. 최신 메시지 시각 내림차순.\n\n" + "- 로그인한 채팅방 참여자 (고객/소유 매장 사장님)",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd4814092c0d8b25f948a50"))
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
                                    "id": "room_001",
                                    "store": {
                                      "id": "shop_moment",
                                      "name": "네일 모먼트",
                                      "imageUrl": null
                                    },
                                    "customer": {
                                      "id": "usr_001",
                                      "displayName": "빈틈고객"
                                    },
                                    "status": "OPEN",
                                    "lastMessage": null,
                                    "unreadCount": 0,
                                    "lastReadSequence": 0,
                                    "createdAt": "2026-10-16T14:00:00+09:00"
                                  }
                                ],
                                "page": {
                                  "nextCursor": null,
                                  "hasNext": false
                                }
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/chatting/rooms"
                            }
                          }
                          """)))
  @GetMapping(value = "/api/v1/chatting/rooms", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<ChatRoomListResponse>> getChatRooms(
      @AuthenticationPrincipal BintimePrincipal principal,
      @RequestParam(value = "cursor", required = false) String cursor,
      @io.swagger.v3.oas.annotations.Parameter(
              schema = @io.swagger.v3.oas.annotations.media.Schema(minimum = "1", maximum = "100"))
          @RequestParam(value = "limit", required = false, defaultValue = "20")
          Integer limit) {
    return ResponseEntity.ok(
        com.bintime.common.dto.ApiResponse.success(service.list(userId(principal), cursor, limit)));
  }

  @Operation(
      operationId = "updateReadPosition",
      summary = "C-05 · 채팅방 읽음 위치 갱신",
      description =
          "읽음 위치는 뒤로 이동하지 않음. 최신 sequence보다 크면 400.\n\n"
              + "- 로그인한 채팅방 참여자 (고객/소유 매장 사장님)\n"
              + "- X-CSRF-Token 필수",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd4810387e7d5894f2fa8d1"))
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
                                "roomId": "room_001",
                                "lastReadSequence": 1,
                                "readAt": "2026-10-16T14:20:00+09:00"
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/chatting/rooms/{roomId}/read"
                            }
                          }
                          """)))
  @ApiResponse(responseCode = "400", description = "읽음 위치가 최신 메시지 sequence보다 큼", content = @Content)
  @PutMapping(
      value = "/api/v1/chatting/rooms/{roomId}/read",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<ChatReadResponse>> updateReadPosition(
      @AuthenticationPrincipal BintimePrincipal principal,
      @PathVariable("roomId") String roomId,
      @io.swagger.v3.oas.annotations.Parameter(
              description = "현재 로그인 세션의 CSRF 토큰. 누락·불일치는 403 CSRF_INVALID",
              required = true)
          @RequestHeader(value = "X-CSRF-Token", required = false)
          String csrfToken,
      @RequestBody UpdateChatReadRequest request) {
    return ResponseEntity.ok(
        com.bintime.common.dto.ApiResponse.success(
            service.read(userId(principal), roomId, request.lastReadSequence())));
  }

  private static String userId(BintimePrincipal principal) {
    com.bintime.common.web.ApiBusinessException.require(principal != null, 401, "UNAUTHORIZED");
    return principal.userId();
  }
}
