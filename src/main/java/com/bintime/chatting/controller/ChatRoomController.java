package com.bintime.chatting.controller;

import com.bintime.chatting.dto.ChatReadResponse;
import com.bintime.chatting.dto.ChatRoomListResponse;
import com.bintime.chatting.dto.ChatRoomResponse;
import com.bintime.chatting.dto.CreateChatRoomRequest;
import com.bintime.chatting.dto.UpdateChatReadRequest;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
public class ChatRoomController {

  // 실제 처리 전까지 성공으로 오인되지 않도록 모든 요청에 501을 반환한다.
  @Operation(
      operationId = "createChatRoom",
      summary = "C-01 · 매장 문의 채팅방 생성·조회",
      description =
          "고객+매장당 방 1개. 신규 201, 기존 200. Idempotency-Key 필수.\n\n"
              + "- CUSTOMER — 본인 데이터\n"
              + "- X-CSRF-Token 필수\n"
              + "- Idempotency-Key 필수 (24시간 재생)\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd4813abd64ce628e66ba48"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "201", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(
      responseCode = "200",
      description = "기존 리소스 또는 동일 요청 재사용",
      useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @PostMapping(
      value = "/api/v1/chatting/rooms",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<ChatRoomResponse>> createChatRoom(
      @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody CreateChatRoomRequest request) {
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }

  @Operation(
      operationId = "getChatRooms",
      summary = "C-02 · 참여 채팅방 목록 조회",
      description =
          "고객은 본인, 사장님은 소유 매장 방만. 최신 메시지 시각 내림차순.\n\n"
              + "- 로그인한 채팅방 참여자 (고객/소유 매장 사장님)\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd4814092c0d8b25f948a50"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @GetMapping(value = "/api/v1/chatting/rooms", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<ChatRoomListResponse>> getChatRooms(
      @RequestParam(value = "cursor", required = false) String cursor,
      @io.swagger.v3.oas.annotations.Parameter(
              schema = @io.swagger.v3.oas.annotations.media.Schema(minimum = "1", maximum = "100"))
          @RequestParam(value = "limit", required = false, defaultValue = "20")
          Integer limit) {
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }

  @Operation(
      operationId = "updateReadPosition",
      summary = "C-05 · 채팅방 읽음 위치 갱신",
      description =
          "읽음 위치는 뒤로 이동하지 않음. 최신 sequence보다 크면 400.\n\n"
              + "- 로그인한 채팅방 참여자 (고객/소유 매장 사장님)\n"
              + "- X-CSRF-Token 필수\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd4810387e7d5894f2fa8d1"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "400", description = "읽음 위치가 최신 메시지 sequence보다 큼", content = @Content)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @PutMapping(
      value = "/api/v1/chatting/rooms/{roomId}/read",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<ChatReadResponse>> updateReadPosition(
      @PathVariable("roomId") String roomId,
      @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
      @RequestBody UpdateChatReadRequest request) {
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }
}
