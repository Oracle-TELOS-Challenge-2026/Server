package com.bintime.store.controller;

import com.bintime.store.dto.OwnerReservationListResponse;
import com.bintime.store.dto.OwnerReservationResponse;
import com.bintime.store.dto.UpdateReservationStatusRequest;
import com.bintime.store.dto.UpdateReservationStatusResponse;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "store")
public class OwnerReservationController {

  private final com.bintime.store.service.StoreApplicationService svc;
  private final com.bintime.common.auth.CurrentActor actor;

  public OwnerReservationController(
      @org.springframework.context.annotation.Lazy
          com.bintime.store.service.StoreApplicationService svc,
      com.bintime.common.auth.CurrentActor actor) {
    this.svc = svc;
    this.actor = actor;
  }

  @Operation(
      operationId = "getOwnerReservation",
      summary = "ST-12 · 사장님 예약 상세 조회",
      description =
          "본인 매장 예약만 조회. 고객 표시 이름과 자원 ID 포함.\n\n"
              + "- OWNER — 해당 매장 소유자\n\n"
              + "Oracle 영속 데이터와 도메인 규칙을 검증합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd4817c8755dc27b321fa8c"))
  @SecurityRequirement(name = "sessionCookie")
  @SecurityRequirement(name = "bearerJwt")
  @ApiResponse(
      responseCode = "200",
      description = "성공",
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
                                "id": "rsv_001",
                                "store": {
                                  "id": "shop_moment",
                                  "name": "네일 모먼트",
                                  "imageUrl": null
                                },
                                "service": {
                                  "id": "svc_nail_01",
                                  "name": "원컬러 젤 네일",
                                  "durationMinutes": 60
                                },
                                "slotId": "slot_1700",
                                "status": "CONFIRMED",
                                "startAt": "2026-10-16T17:00:00+09:00",
                                "endAt": "2026-10-16T18:00:00+09:00",
                                "price": {
                                  "original": 50000,
                                  "payable": 35000,
                                  "currency": "KRW"
                                },
                                "waitlistId": "wait_001",
                                "cancelReasonCode": null,
                                "canceledAt": null,
                                "createdAt": "2026-10-16T14:20:00+09:00",
                                "version": 1,
                                "allowedActions": [
                                  "VIEW_STORE",
                                  "OPEN_CHAT",
                                  "CANCEL"
                                ],
                                "customer": {
                                  "id": "usr_001",
                                  "displayName": "빈틈고객"
                                },
                                "resourceId": "staff_01"
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/owner/stores/{storeId}/reservations/{reservationId}"
                            }
                          }
                          """)))
  @ApiResponse(responseCode = "401", description = "인증 필요")
  @ApiResponse(responseCode = "403", description = "권한 부족 또는 CSRF 토큰 없음·불일치")
  @ApiResponse(responseCode = "404", description = "리소스 없음")
  @GetMapping(
      value = "/api/v1/owner/stores/{storeId}/reservations/{reservationId}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<OwnerReservationResponse>>
      getReservation(
          @PathVariable("storeId") String storeId,
          @PathVariable("reservationId") String reservationId) {
    return ResponseEntity.ok()
        .body(
            com.bintime.common.dto.ApiResponse.success(
                svc.ownerReservation(actor.get(), storeId, reservationId)));
  }

  @Operation(
      operationId = "updateReservationStatus",
      summary = "ST-13 · 예약 신청 승인·거절 및 시술 완료·노쇼 처리",
      description =
          "Idempotency-Key 필수. PENDING 신청은 CONFIRMED/REJECTED로 승인·거절한다. 승인 전 선점은 없고 한 건 승인 시 같은 슬롯의"
              + " 나머지 신청을 거절한다. 승인 시 권한·시간·서비스·가격·일정·대기 조건을 재검증한다. COMPLETED/NO_SHOW는 확정 예약만 허용."
              + " 완료는 종료 이후, 노쇼는 시작+10분 이후. 노쇼의 종료 전 남은 구간이면 vacancy 생성.\n\n"
              + "- OWNER — 해당 매장 소유자\n"
              + "- X-CSRF-Token 필수\n"
              + "- Idempotency-Key 필수 (24시간 재생)\n\n"
              + "Oracle 영속 데이터와 도메인 규칙을 검증합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481d7a2e4fba29a1d7554"))
  @SecurityRequirement(name = "sessionCookie")
  @SecurityRequirement(name = "bearerJwt")
  @ApiResponse(
      responseCode = "200",
      description = "성공",
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
                                "reservation": {
                                  "id": "rsv_001",
                                  "store": {
                                    "id": "shop_moment",
                                    "name": "네일 모먼트",
                                    "imageUrl": null
                                  },
                                  "service": {
                                    "id": "svc_nail_01",
                                    "name": "원컬러 젤 네일",
                                    "durationMinutes": 60
                                  },
                                  "slotId": "slot_1700",
                                  "status": "COMPLETED",
                                  "startAt": "2026-10-16T17:00:00+09:00",
                                  "endAt": "2026-10-16T18:00:00+09:00",
                                  "price": {
                                    "original": 50000,
                                    "payable": 35000,
                                    "currency": "KRW"
                                  },
                                  "waitlistId": "wait_001",
                                  "cancelReasonCode": null,
                                  "canceledAt": null,
                                  "createdAt": "2026-10-16T14:20:00+09:00",
                                  "version": 2,
                                  "allowedActions": [
                                    "VIEW_STORE",
                                    "OPEN_CHAT",
                                    "REBOOK"
                                  ],
                                  "customer": {
                                    "id": "usr_001",
                                    "displayName": "빈틈고객"
                                  },
                                  "resourceId": "staff_01"
                                },
                                "vacancy": null
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/owner/stores/{storeId}/reservations/{reservationId}/status"
                            }
                          }
                          """)))
  @ApiResponse(responseCode = "400", description = "입력 형식 또는 조건 오류")
  @ApiResponse(responseCode = "401", description = "인증 필요")
  @ApiResponse(responseCode = "403", description = "권한 부족 또는 CSRF 토큰 없음·불일치")
  @ApiResponse(responseCode = "404", description = "리소스 없음")
  @ApiResponse(responseCode = "409", description = "상태·버전·시간·가격 충돌")
  @PostMapping(
      value = "/api/v1/owner/stores/{storeId}/reservations/{reservationId}/status",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<UpdateReservationStatusResponse>>
      updateReservationStatus(
          @PathVariable("storeId") String storeId,
          @PathVariable("reservationId") String reservationId,
          @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
          @RequestHeader("Idempotency-Key") String idempotencyKey,
          @RequestBody UpdateReservationStatusRequest request) {
    return ResponseEntity.ok()
        .body(
            com.bintime.common.dto.ApiResponse.success(
                svc.updateStatus(actor.get(), storeId, reservationId, idempotencyKey, request)));
  }

  @Operation(
      operationId = "getOwnerReservationRequests",
      summary = "ST-21 · 빈자리 승인 대기 신청 목록",
      description = "해당 매장 소유자만 조회. 신청 시각·ID 오름차순으로 선점 없는 PENDING 신청을 반환한다. 시술 시작 시 만료한다.")
  @SecurityRequirement(name = "sessionCookie")
  @SecurityRequirement(name = "bearerJwt")
  @ApiResponse(
      responseCode = "200",
      description = "성공",
      useReturnTypeSchema = true,
      content =
          @Content(
              mediaType = "application/json",
              examples =
                  @ExampleObject(
                      name = "success",
                      value =
                          "{\"resultType\":\"SUCCESS\",\"success\":{\"data\":{\"items\":[],\"page\":{\"nextCursor\":null,\"hasNext\":false}}},\"error\":null,\"meta\":{\"timestamp\":\"2026-10-17T12:00:00\",\"path\":\"/api/v1/owner/stores/shop_1/reservation-requests\"}}")))
  @ApiResponse(responseCode = "400", description = "잘못된 커서·limit")
  @ApiResponse(responseCode = "401", description = "인증 필요")
  @ApiResponse(responseCode = "403", description = "해당 매장 소유자만 조회 가능")
  @ApiResponse(responseCode = "404", description = "STORE_NOT_FOUND")
  @GetMapping(
      value = "/api/v1/owner/stores/{storeId}/reservation-requests",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<OwnerReservationListResponse>>
      getReservationRequests(
          @PathVariable String storeId,
          @RequestParam(required = false) String cursor,
          @RequestParam(defaultValue = "20") Integer limit) {
    return ResponseEntity.ok(
        com.bintime.common.dto.ApiResponse.success(
            svc.reservationRequests(actor.get(), storeId, cursor, limit)));
  }
}
