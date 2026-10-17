package com.bintime.chatting.controller;

import static com.bintime.common.web.ApiBusinessException.require;

import com.bintime.chatting.dto.ChatBookingSuggestionResponse;
import com.bintime.chatting.service.ChatBookingSuggestionService;
import com.bintime.user.security.BintimePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "chatting")
@SecurityRequirement(name = "sessionCookie")
public class ChatBookingSuggestionController {
  private final ChatBookingSuggestionService suggestions;

  public ChatBookingSuggestionController(@Lazy ChatBookingSuggestionService suggestions) {
    this.suggestions = suggestions;
  }

  @Operation(
      operationId = "analyzeChatBookingSuggestion",
      summary = "C-08 · 고객 메시지 예약 의사·날짜·시간·시술 추출",
      description =
          "해당 방 참여자만 최신 고객 TEXT를 기준으로 최근 고객 메시지 최대 5개를 시간순 분석한다. 본문 없이 호출하며 X-CSRF-Token 필수. 원문·발송"
              + " 시각은 DB에서 읽는다. 나눠 보낸 요청을 합치고 나중 정정·취소를 우선한다. 인사만 있거나 취소된 요청은"
              + " bookingRequested=false. 불명확한 필드는 null. 사장님 검토용이며 예약·제안·알림을 생성하거나 시간을 선점하지 않는다. 새"
              + " 고객 메시지가 도착하면 409 PROPOSAL_SOURCE_OUTDATED. 분석 결과는 메시지 ID별 최대 256개 메모리 캐시이며 재시작 후"
              + " 재분석한다. contextMessages는 분석 원문 목록이다. 확정된 제안 이전 대화는 제외하며 확정 시 캐시도 무효화한다.")
  @ApiResponse(
      responseCode = "200",
      description = "분석 완료",
      useReturnTypeSchema = true,
      content =
          @Content(
              mediaType = "application/json",
              examples =
                  @ExampleObject(
                      name = "success",
                      value =
                          """
                          {"resultType":"SUCCESS","success":{"data":{
                            "sourceMessageId":"msg_customer_005","sourceContent":"옵션 없이요",
                            "bookingRequested":true,"date":"2026-10-18","time":"17:00","serviceDescription":"커트",
                            "contextMessages":[{"id":"msg_customer_001","sequence":1,"content":"예약하고 싶어요","createdAt":"2026-10-17T14:00:01+09:00"},{"id":"msg_customer_002","sequence":2,"content":"내일이요","createdAt":"2026-10-17T14:00:02+09:00"},{"id":"msg_customer_003","sequence":3,"content":"17시에요","createdAt":"2026-10-17T14:00:03+09:00"},{"id":"msg_customer_004","sequence":4,"content":"머리 자르려고요","createdAt":"2026-10-17T14:00:04+09:00"},{"id":"msg_customer_005","sequence":5,"content":"옵션 없이요","createdAt":"2026-10-17T14:00:05+09:00"}]
                          }},"error":null,"meta":{"timestamp":"2026-10-17T14:00:00",
                            "path":"/api/v1/chatting/rooms/{roomId}/messages/{messageId}/booking-suggestion"}}
                          """)))
  @ApiResponse(responseCode = "400", description = "INVALID_ID", content = @Content)
  @ApiResponse(responseCode = "401", description = "UNAUTHORIZED", content = @Content)
  @ApiResponse(responseCode = "403", description = "CSRF_INVALID", content = @Content)
  @ApiResponse(
      responseCode = "404",
      description = "ROOM_NOT_FOUND / SOURCE_MESSAGE_NOT_FOUND",
      content = @Content)
  @ApiResponse(
      responseCode = "409",
      description = "PROPOSAL_SOURCE_OUTDATED / ROOM_READ_ONLY",
      content = @Content)
  @ApiResponse(
      responseCode = "503",
      description = "OPENAI_NOT_CONFIGURED / CHAT_BOOKING_ANALYSIS_UNAVAILABLE. 원문 채팅은 유지되며 분석만 실패",
      content = @Content)
  @PostMapping(
      value = "/api/v1/chatting/rooms/{roomId}/messages/{messageId}/booking-suggestion",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<ChatBookingSuggestionResponse>> analyze(
      @AuthenticationPrincipal BintimePrincipal principal,
      @PathVariable String roomId,
      @PathVariable String messageId,
      @Parameter(description = "현재 로그인 세션의 CSRF 토큰", required = true)
          @RequestHeader(value = "X-CSRF-Token", required = false)
          String csrfToken) {
    require(principal != null, 401, "UNAUTHORIZED");
    return ResponseEntity.ok(
        com.bintime.common.dto.ApiResponse.success(
            suggestions.analyze(principal.userId(), roomId, messageId)));
  }
}
