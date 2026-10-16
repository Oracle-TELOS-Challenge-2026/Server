package com.bintime.store.controller;

import com.bintime.store.dto.CancelReservationRequest;
import com.bintime.store.dto.CancelReservationResponse;
import com.bintime.store.dto.CreateReservationRequest;
import com.bintime.store.dto.ReservationListResponse;
import com.bintime.store.dto.ReservationResponse;
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
public class ReservationController {

  private final com.bintime.store.service.StoreApplicationService svc;
  private final com.bintime.common.auth.CurrentActor actor;

  public ReservationController(
      @org.springframework.context.annotation.Lazy
          com.bintime.store.service.StoreApplicationService svc,
      com.bintime.common.auth.CurrentActor actor) {
    this.svc = svc;
    this.actor = actor;
  }

  @Operation(
      operationId = "createReservation",
      summary = "ST-06 · 예약 확정",
      description =
          "Idempotency-Key 필수. 자원 시간·고객 시간·대기를 잠금 처리. 409"
              + " SLOT_UNAVAILABLE/PRICE_CHANGED/RESERVATION_TIME_CONFLICT.\n\n"
              + "- CUSTOMER — 본인 데이터\n"
              + "- X-CSRF-Token 필수\n"
              + "- Idempotency-Key 필수 (24시간 재생)\n\n"
              + "Oracle 영속 데이터와 도메인 규칙을 검증합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481278f63f09125038da1"))
  @SecurityRequirement(name = "sessionCookie")
  @SecurityRequirement(name = "bearerJwt")
  @ApiResponse(
      responseCode = "201",
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
                                ]
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/reservations"
                            }
                          }
                          """)))
  @ApiResponse(
      responseCode = "409",
      description =
          "SLOT_UNAVAILABLE / PRICE_CHANGED / RESERVATION_TIME_CONFLICT: 슬롯 이용 불가, 가격 변경 또는 고객 시간"
              + " 충돌",
      content = @Content)
  @ApiResponse(responseCode = "400", description = "입력 형식 또는 조건 오류")
  @ApiResponse(responseCode = "401", description = "인증 필요")
  @ApiResponse(responseCode = "403", description = "권한 부족 또는 CSRF 토큰 없음·불일치")
  @ApiResponse(responseCode = "404", description = "리소스 없음")
  @PostMapping(
      value = "/api/v1/reservations",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<ReservationResponse>> createReservation(
      @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody CreateReservationRequest request) {
    return ResponseEntity.status(201)
        .body(
            com.bintime.common.dto.ApiResponse.success(
                svc.reserve(actor.get(), idempotencyKey, request)));
  }

  @Operation(
      operationId = "getMyReservations",
      summary = "ST-07 · 내 예약 내역 조회",
      description =
          "다가오는/지난/취소 목록. counts는 전체 기준이며 예시 목록은 1개 행을 보여준다.\n\n"
              + "- CUSTOMER — 본인 데이터\n\n"
              + "Oracle 영속 데이터와 도메인 규칙을 검증합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd4817b813ad3029a4c241c"))
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
                                "items": [
                                  {
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
                                    ]
                                  }
                                ],
                                "page": {
                                  "nextCursor": null,
                                  "hasNext": false
                                },
                                "counts": {
                                  "upcoming": 2,
                                  "past": 1,
                                  "canceled": 0
                                }
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/reservations/me"
                            }
                          }
                          """)))
  @ApiResponse(responseCode = "400", description = "입력 형식 또는 조건 오류")
  @ApiResponse(responseCode = "401", description = "인증 필요")
  @ApiResponse(responseCode = "403", description = "권한 부족 또는 CSRF 토큰 없음·불일치")
  @GetMapping(value = "/api/v1/reservations/me", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<ReservationListResponse>>
      getMyReservations(
          @io.swagger.v3.oas.annotations.Parameter(
                  schema =
                      @io.swagger.v3.oas.annotations.media.Schema(
                          allowableValues = {"UPCOMING", "PAST", "CANCELED"}))
              @RequestParam(value = "scope", required = true)
              String scope,
          @RequestParam(value = "cursor", required = false) String cursor,
          @io.swagger.v3.oas.annotations.Parameter(
                  schema =
                      @io.swagger.v3.oas.annotations.media.Schema(minimum = "1", maximum = "100"))
              @RequestParam(value = "limit", required = false, defaultValue = "20")
              Integer limit) {
    return ResponseEntity.ok()
        .body(
            com.bintime.common.dto.ApiResponse.success(
                svc.myReservations(actor.get(), scope, cursor, limit)));
  }

  @Operation(
      operationId = "getReservation",
      summary = "ST-08 · 예약 상세 조회",
      description =
          "본인 또는 해당 매장 사장님만 접근. OWNER는 customer·resourceId 확장 필드를 추가 반환.\n\n"
              + "- 예약 본인 또는 해당 매장 OWNER\n\n"
              + "Oracle 영속 데이터와 도메인 규칙을 검증합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481ec9983fd64ba822751"))
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
                                ]
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/reservations/{reservationId}"
                            }
                          }
                          """)))
  @ApiResponse(responseCode = "401", description = "인증 필요")
  @ApiResponse(responseCode = "403", description = "권한 부족 또는 CSRF 토큰 없음·불일치")
  @ApiResponse(responseCode = "404", description = "리소스 없음")
  @GetMapping(
      value = "/api/v1/reservations/{reservationId}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<ReservationResponse>> getReservation(
      @PathVariable("reservationId") String reservationId) {
    return ResponseEntity.ok()
        .body(
            com.bintime.common.dto.ApiResponse.success(
                svc.getReservation(actor.get(), reservationId)));
  }

  @Operation(
      operationId = "cancelReservation",
      summary = "ST-09 · 예약 취소 및 빈자리 생성",
      description =
          "Idempotency-Key 필수. CONFIRMED→CANCELED. 원예약당 Vacancy 최대 1개. 종료한 예약은 vacancy=null.\n\n"
              + "- 예약 본인 또는 해당 매장 OWNER\n"
              + "- X-CSRF-Token 필수\n"
              + "- Idempotency-Key 필수 (24시간 재생)\n\n"
              + "Oracle 영속 데이터와 도메인 규칙을 검증합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd48174b700df232299fa77"))
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
                                  "status": "CANCELED",
                                  "startAt": "2026-10-16T17:00:00+09:00",
                                  "endAt": "2026-10-16T18:00:00+09:00",
                                  "price": {
                                    "original": 50000,
                                    "payable": 35000,
                                    "currency": "KRW"
                                  },
                                  "waitlistId": "wait_001",
                                  "cancelReasonCode": "CUSTOMER_REQUEST",
                                  "canceledAt": "2026-10-16T14:20:00+09:00",
                                  "createdAt": "2026-10-16T14:20:00+09:00",
                                  "version": 2,
                                  "allowedActions": [
                                    "VIEW_STORE",
                                    "OPEN_CHAT",
                                    "REBOOK"
                                  ]
                                },
                                "vacancy": {
                                  "id": "vac_001",
                                  "storeId": "shop_moment",
                                  "sourceReservationId": "rsv_old",
                                  "reason": "CANCELLATION",
                                  "status": "DRAFT",
                                  "resourceId": "staff_01",
                                  "availableFrom": "2026-10-16T17:00:00+09:00",
                                  "availableUntil": "2026-10-16T18:00:00+09:00",
                                  "originalServiceId": "svc_nail_01",
                                  "originalPrice": 50000,
                                  "publishedSlotId": null,
                                  "publishedAt": null,
                                  "occurredAt": "2026-10-16T14:10:00+09:00",
                                  "version": 1
                                }
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/reservations/{reservationId}/cancel"
                            }
                          }
                          """)))
  @ApiResponse(responseCode = "400", description = "입력 형식 또는 조건 오류")
  @ApiResponse(responseCode = "401", description = "인증 필요")
  @ApiResponse(responseCode = "403", description = "권한 부족 또는 CSRF 토큰 없음·불일치")
  @ApiResponse(responseCode = "404", description = "리소스 없음")
  @ApiResponse(responseCode = "409", description = "상태·버전·시간·가격 충돌")
  @PostMapping(
      value = "/api/v1/reservations/{reservationId}/cancel",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<CancelReservationResponse>>
      cancelReservation(
          @PathVariable("reservationId") String reservationId,
          @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
          @RequestHeader("Idempotency-Key") String idempotencyKey,
          @RequestBody CancelReservationRequest request) {
    return ResponseEntity.ok()
        .body(
            com.bintime.common.dto.ApiResponse.success(
                svc.cancel(actor.get(), reservationId, idempotencyKey, request)));
  }
}
