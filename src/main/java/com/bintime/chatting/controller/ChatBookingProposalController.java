package com.bintime.chatting.controller;

import static com.bintime.common.web.ApiBusinessException.require;

import com.bintime.chatting.dto.ChatMessageResponse;
import com.bintime.chatting.dto.ConfirmBookingProposalRequest;
import com.bintime.chatting.dto.CreateBookingProposalRequest;
import com.bintime.chatting.service.ChatService;
import com.bintime.common.dto.ApiResponse;
import com.bintime.user.security.BintimePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "chatting")
@SecurityRequirement(name = "sessionCookie")
@io.swagger.v3.oas.annotations.responses.ApiResponse(
    responseCode = "400",
    description = "입력 형식 오류 또는 필수 헤더 없음",
    content = @Content)
@io.swagger.v3.oas.annotations.responses.ApiResponse(
    responseCode = "401",
    description = "로그인 세션 필요",
    content = @Content)
@io.swagger.v3.oas.annotations.responses.ApiResponse(
    responseCode = "403",
    description = "OWNER_REQUIRED / CUSTOMER_REQUIRED 또는 CSRF_INVALID",
    content = @Content)
@io.swagger.v3.oas.annotations.responses.ApiResponse(
    responseCode = "404",
    description = "ROOM_NOT_FOUND / SOURCE_MESSAGE_NOT_FOUND / PROPOSAL_NOT_FOUND 또는 매장·시술 없음",
    content = @Content)
public class ChatBookingProposalController {
  private final ChatService service;

  public ChatBookingProposalController(@Lazy ChatService service) {
    this.service = service;
  }

  @Operation(
      operationId = "createBookingProposal",
      summary = "C-06 · 사장님 예약 제안 생성",
      description =
          "해당 매장 사장님만 생성. 가장 최근 고객 TEXT 메시지를 sourceMessageId로 지정한다. "
              + "사장님이 확인한 설명·시간·금액을 별도로 저장하며 슬롯을 선점하지 않는다. "
              + "이전 미확정 제안은 SUPERSEDED로 변경한다.\n\n"
              + "X-CSRF-Token 및 Idempotency-Key 필수. 동일 요청의 성공 응답과 상태를 24시간 재생한다.")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "201",
      description = "예약 제안 카드 생성",
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
                                "id": "msg_proposal_001",
                                "roomId": "room_001",
                                "sequence": 2,
                                "sender": {
                                  "id": "usr_owner_001",
                                  "role": "OWNER",
                                  "displayName": "네일 모먼트"
                                },
                                "clientMessageId": "proposal_001",
                                "type": "BOOKING_PROPOSAL",
                                "content": "예약 제안: 젤 네일 + 기존 젤 제거 + 아트 2개",
                                "createdAt": "2026-10-17T14:00:00+09:00",
                                "bookingProposal": {
                                  "id": "proposal_001",
                                  "sourceMessageId": "msg_customer_001",
                                  "serviceId": "svc_gel",
                                  "serviceDescription": "젤 네일 + 기존 젤 제거 + 아트 2개",
                                  "startAt": "2026-10-20T14:00:00+09:00",
                                  "endAt": "2026-10-20T15:30:00+09:00",
                                  "durationMinutes": 90,
                                  "price": 75000,
                                  "status": "PENDING",
                                  "version": 1,
                                  "reservationId": null,
                                  "waitlistId": null
                                }
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-17T14:00:00",
                              "path": "/api/v1/chatting/rooms/{roomId}/booking-proposals"
                            }
                          }
                          """)))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "409",
      description = "PROPOSAL_SOURCE_OUTDATED / ROOM_READ_ONLY / IDEMPOTENCY_KEY_REUSED",
      content = @Content)
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "429",
      description = "RATE_LIMITED: 사용자별 신규 텍스트·예약 제안 합산 초당 5건 한도 초과",
      content = @Content)
  @PostMapping(
      value = "/api/v1/chatting/rooms/{roomId}/booking-proposals",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<ChatMessageResponse>> createBookingProposal(
      @AuthenticationPrincipal BintimePrincipal principal,
      @PathVariable("roomId") String roomId,
      @Parameter(description = "현재 로그인 세션의 CSRF 토큰", required = true)
          @RequestHeader(value = "X-CSRF-Token", required = false)
          String csrfToken,
      @Parameter(description = "최초 생성 요청과 재시도에 유지하는 키", required = true)
          @RequestHeader("Idempotency-Key")
          String idempotencyKey,
      @RequestBody CreateBookingProposalRequest request) {
    var result = service.propose(userId(principal), roomId, idempotencyKey, request);
    return ResponseEntity.status(result.status()).body(ApiResponse.success(result.data()));
  }

  @Operation(
      operationId = "confirmBookingProposal",
      summary = "C-07 · 고객 예약 제안 확정",
      description =
          "해당 방 고객만 확정. expectedVersion을 검증하고 서버에 저장된 제안 설명·시간·금액을 사용한다. "
              + "영업시간·고객 일정·매장 자원 및 기존 예약·대기 규칙을 다시 검증한다. "
              + "예약 생성과 제안 CONFIRMED 갱신은 같은 트랜잭션이다.\n\n"
              + "X-CSRF-Token 및 Idempotency-Key 필수. 동일 요청의 성공 응답과 상태를 24시간 재생한다.")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      description = "예약 생성 및 제안 확정",
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
                                "id": "msg_proposal_001",
                                "roomId": "room_001",
                                "sequence": 2,
                                "sender": {
                                  "id": "usr_owner_001",
                                  "role": "OWNER",
                                  "displayName": "네일 모먼트"
                                },
                                "clientMessageId": "proposal_001",
                                "type": "BOOKING_PROPOSAL",
                                "content": "예약 제안: 젤 네일 + 기존 젤 제거 + 아트 2개",
                                "createdAt": "2026-10-17T14:00:00+09:00",
                                "bookingProposal": {
                                  "id": "proposal_001",
                                  "sourceMessageId": "msg_customer_001",
                                  "serviceId": "svc_gel",
                                  "serviceDescription": "젤 네일 + 기존 젤 제거 + 아트 2개",
                                  "startAt": "2026-10-20T14:00:00+09:00",
                                  "endAt": "2026-10-20T15:30:00+09:00",
                                  "durationMinutes": 90,
                                  "price": 75000,
                                  "status": "CONFIRMED",
                                  "version": 2,
                                  "reservationId": "rsv_001",
                                  "waitlistId": null
                                }
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-17T14:00:00",
                              "path": "/api/v1/chatting/rooms/{roomId}/booking-proposals/{proposalId}/confirm"
                            }
                          }
                          """)))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "409",
      description =
          "PROPOSAL_SUPERSEDED / PROPOSAL_REVIEW_REQUIRED / PROPOSAL_VERSION_CONFLICT / "
              + "PROPOSAL_ALREADY_CONFIRMED / RESERVATION_TIME_CONFLICT / IDEMPOTENCY_KEY_REUSED. "
              + "그 외 영업시간·시술·대기 오류는 기존 store 오류 코드를 유지한다",
      content = @Content)
  @PostMapping(
      value = "/api/v1/chatting/rooms/{roomId}/booking-proposals/{proposalId}/confirm",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<ChatMessageResponse>> confirmBookingProposal(
      @AuthenticationPrincipal BintimePrincipal principal,
      @PathVariable("roomId") String roomId,
      @PathVariable("proposalId") String proposalId,
      @Parameter(description = "현재 로그인 세션의 CSRF 토큰", required = true)
          @RequestHeader(value = "X-CSRF-Token", required = false)
          String csrfToken,
      @Parameter(description = "최초 확정 요청과 재시도에 유지하는 키", required = true)
          @RequestHeader("Idempotency-Key")
          String idempotencyKey,
      @RequestBody ConfirmBookingProposalRequest request) {
    var result = service.confirm(userId(principal), roomId, proposalId, idempotencyKey, request);
    return ResponseEntity.status(result.status()).body(ApiResponse.success(result.data()));
  }

  private static String userId(BintimePrincipal principal) {
    require(principal != null, 401, "UNAUTHORIZED");
    return principal.userId();
  }
}
