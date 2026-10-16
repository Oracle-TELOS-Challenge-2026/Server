package com.bintime.store.controller;

import com.bintime.store.dto.PublishVacancyRequest;
import com.bintime.store.dto.PublishVacancyResponse;
import com.bintime.store.dto.VacancyListResponse;
import com.bintime.store.dto.WithdrawVacancyRequest;
import com.bintime.store.dto.WithdrawVacancyResponse;
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
public class OwnerVacancyController {

  private final com.bintime.store.service.StoreApplicationService svc;
  private final com.bintime.common.auth.CurrentActor actor;

  public OwnerVacancyController(
      @org.springframework.context.annotation.Lazy
          com.bintime.store.service.StoreApplicationService svc,
      com.bintime.common.auth.CurrentActor actor) {
    this.svc = svc;
    this.actor = actor;
  }

  @Operation(
      operationId = "getVacancies",
      summary = "ST-14 · 취소·노쇼 빈자리 목록 조회",
      description =
          "occurredAt 내림차순. 미발행 DRAFT와 발행 완료 상태를 구분한다.\n\n"
              + "- OWNER — 해당 매장 소유자\n\n"
              + "Oracle 영속 데이터와 도메인 규칙을 검증합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481a49fc5db03d18be940"))
  @SecurityRequirement(name = "sessionCookie")
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
                                ],
                                "page": {
                                  "nextCursor": null,
                                  "hasNext": false
                                },
                                "counts": {
                                  "awaitingPublication": 2,
                                  "published": 1,
                                  "filled": 0
                                }
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/owner/stores/{storeId}/vacancies"
                            }
                          }
                          """)))
  @ApiResponse(responseCode = "400", description = "입력 형식 또는 조건 오류")
  @ApiResponse(responseCode = "401", description = "인증 필요")
  @ApiResponse(responseCode = "403", description = "권한 부족 또는 인증 연동 미완료")
  @ApiResponse(responseCode = "404", description = "리소스 없음")
  @GetMapping(
      value = "/api/v1/owner/stores/{storeId}/vacancies",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<VacancyListResponse>> getVacancies(
      @PathVariable("storeId") String storeId,
      @io.swagger.v3.oas.annotations.Parameter(
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      allowableValues = {"DRAFT", "PUBLISHED", "FILLED", "WITHDRAWN", "EXPIRED"}))
          @RequestParam(value = "status", required = false)
          String status,
      @RequestParam(value = "cursor", required = false) String cursor,
      @io.swagger.v3.oas.annotations.Parameter(
              schema = @io.swagger.v3.oas.annotations.media.Schema(minimum = "1", maximum = "100"))
          @RequestParam(value = "limit", required = false, defaultValue = "20")
          Integer limit) {
    return ResponseEntity.ok()
        .body(
            com.bintime.common.dto.ApiResponse.success(
                svc.vacancies(actor.get(), storeId, status, cursor, limit)));
  }

  @Operation(
      operationId = "publishVacancy",
      summary = "ST-15 · 빈자리 특가 발행",
      description =
          "Idempotency-Key 필수. 등록된 시술만 선택. discount=PERCENT/FIXED_PRICE. 시술 전체가 남은 시간에 맞아야 함. 200은"
              + " 발행 완료이며 알림 도착 완료가 아님.\n\n"
              + "- OWNER — 해당 매장 소유자\n"
              + "- X-CSRF-Token 필수\n"
              + "- Idempotency-Key 필수 (24시간 재생)\n\n"
              + "Oracle 영속 데이터와 도메인 규칙을 검증합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481ae89e8eb205f157f7e"))
  @SecurityRequirement(name = "sessionCookie")
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
                                "vacancy": {
                                  "id": "vac_001",
                                  "storeId": "shop_moment",
                                  "sourceReservationId": "rsv_old",
                                  "reason": "CANCELLATION",
                                  "status": "PUBLISHED",
                                  "resourceId": "staff_01",
                                  "availableFrom": "2026-10-16T17:00:00+09:00",
                                  "availableUntil": "2026-10-16T18:00:00+09:00",
                                  "originalServiceId": "svc_nail_01",
                                  "originalPrice": 50000,
                                  "publishedSlotId": "slot_1700",
                                  "publishedAt": "2026-10-16T14:20:00+09:00",
                                  "occurredAt": "2026-10-16T14:10:00+09:00",
                                  "version": 2
                                },
                                "slot": {
                                  "id": "slot_1700",
                                  "storeId": "shop_moment",
                                  "serviceId": "svc_nail_01",
                                  "resourceId": "staff_01",
                                  "startAt": "2026-10-16T17:00:00+09:00",
                                  "endAt": "2026-10-16T18:00:00+09:00",
                                  "status": "AVAILABLE",
                                  "bookable": true,
                                  "unavailableReason": null,
                                  "source": "VACANCY",
                                  "vacancyId": "vac_001",
                                  "publicationVersion": 3,
                                  "price": {
                                    "original": 50000,
                                    "payable": 35000,
                                    "discountPercent": 30,
                                    "currency": "KRW"
                                  },
                                  "version": 3
                                },
                                "matchingStatus": "QUEUED"
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/owner/stores/{storeId}/vacancies/{vacancyId}/publish"
                            }
                          }
                          """)))
  @ApiResponse(responseCode = "400", description = "입력 형식 또는 조건 오류")
  @ApiResponse(responseCode = "401", description = "인증 필요")
  @ApiResponse(responseCode = "403", description = "권한 부족 또는 인증 연동 미완료")
  @ApiResponse(responseCode = "404", description = "리소스 없음")
  @ApiResponse(responseCode = "409", description = "상태·버전·시간·가격 충돌")
  @PostMapping(
      value = "/api/v1/owner/stores/{storeId}/vacancies/{vacancyId}/publish",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<PublishVacancyResponse>> publishVacancy(
      @PathVariable("storeId") String storeId,
      @PathVariable("vacancyId") String vacancyId,
      @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody PublishVacancyRequest request) {
    return ResponseEntity.ok()
        .body(
            com.bintime.common.dto.ApiResponse.success(
                svc.publish(actor.get(), storeId, vacancyId, idempotencyKey, request)));
  }

  @Operation(
      operationId = "withdrawVacancy",
      summary = "ST-16 · 발행한 빈자리 철회",
      description =
          "Idempotency-Key 필수. 이미 예약된 경우 409 VACANCY_ALREADY_FILLED.\n\n"
              + "- OWNER — 해당 매장 소유자\n"
              + "- X-CSRF-Token 필수\n"
              + "- Idempotency-Key 필수 (24시간 재생)\n\n"
              + "Oracle 영속 데이터와 도메인 규칙을 검증합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481799276c9f05e1a32ec"))
  @SecurityRequirement(name = "sessionCookie")
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
                                "vacancy": {
                                  "id": "vac_001",
                                  "storeId": "shop_moment",
                                  "sourceReservationId": "rsv_old",
                                  "reason": "CANCELLATION",
                                  "status": "WITHDRAWN",
                                  "resourceId": "staff_01",
                                  "availableFrom": "2026-10-16T17:00:00+09:00",
                                  "availableUntil": "2026-10-16T18:00:00+09:00",
                                  "originalServiceId": "svc_nail_01",
                                  "originalPrice": 50000,
                                  "publishedSlotId": "slot_1700",
                                  "publishedAt": "2026-10-16T14:20:00+09:00",
                                  "occurredAt": "2026-10-16T14:10:00+09:00",
                                  "version": 3
                                },
                                "slot": {
                                  "id": "slot_1700",
                                  "storeId": "shop_moment",
                                  "serviceId": "svc_nail_01",
                                  "resourceId": "staff_01",
                                  "startAt": "2026-10-16T17:00:00+09:00",
                                  "endAt": "2026-10-16T18:00:00+09:00",
                                  "status": "BLOCKED",
                                  "bookable": false,
                                  "unavailableReason": "BLOCKED",
                                  "source": "VACANCY",
                                  "vacancyId": "vac_001",
                                  "publicationVersion": 3,
                                  "price": {
                                    "original": 50000,
                                    "payable": 35000,
                                    "discountPercent": 30,
                                    "currency": "KRW"
                                  },
                                  "version": 4
                                }
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/owner/stores/{storeId}/vacancies/{vacancyId}/withdraw"
                            }
                          }
                          """)))
  @ApiResponse(
      responseCode = "409",
      description = "VACANCY_ALREADY_FILLED: 이미 예약된 빈자리",
      content = @Content)
  @ApiResponse(responseCode = "400", description = "입력 형식 또는 조건 오류")
  @ApiResponse(responseCode = "401", description = "인증 필요")
  @ApiResponse(responseCode = "403", description = "권한 부족 또는 인증 연동 미완료")
  @ApiResponse(responseCode = "404", description = "리소스 없음")
  @PostMapping(
      value = "/api/v1/owner/stores/{storeId}/vacancies/{vacancyId}/withdraw",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<WithdrawVacancyResponse>>
      withdrawVacancy(
          @PathVariable("storeId") String storeId,
          @PathVariable("vacancyId") String vacancyId,
          @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
          @RequestHeader("Idempotency-Key") String idempotencyKey,
          @RequestBody WithdrawVacancyRequest request) {
    return ResponseEntity.ok()
        .body(
            com.bintime.common.dto.ApiResponse.success(
                svc.withdraw(actor.get(), storeId, vacancyId, idempotencyKey, request)));
  }
}
