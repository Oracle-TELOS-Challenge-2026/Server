package com.bintime.store.controller;

import com.bintime.store.dto.ReservationSlotResponse;
import com.bintime.store.dto.StoreDetailResponse;
import com.bintime.store.dto.StoreServiceResponse;
import com.bintime.store.dto.TimetableResponse;
import com.bintime.store.dto.UpdateStoreRequest;
import com.bintime.store.dto.UpdateStoreServiceRequest;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "store")
public class OwnerStoreController {

  private final com.bintime.store.service.StoreApplicationService svc;
  private final com.bintime.common.auth.CurrentActor actor;

  public OwnerStoreController(
      @org.springframework.context.annotation.Lazy
          com.bintime.store.service.StoreApplicationService svc,
      com.bintime.common.auth.CurrentActor actor) {
    this.svc = svc;
    this.actor = actor;
  }

  @Operation(
      operationId = "updateStore",
      summary = "ST-10 · 매장 운영 정보 수정",
      description =
          "description 또는 openingHours 중 하나 이상. 해당 매장 사장님만 변경.\n\n"
              + "- OWNER — 해당 매장 소유자\n"
              + "- X-CSRF-Token 필수\n\n"
              + "Oracle 영속 데이터와 도메인 규칙을 검증합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481aab1edf442cb8b4f32"))
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
                                "id": "shop_moment",
                                "name": "네일 모먼트",
                                "category": "NAIL",
                                "description": "취향을 함께 고민하는 따뜻한 네일 공간",
                                "address": "서울 성동구 연무장길 18, 2층",
                                "location": {
                                  "latitude": 37.5445,
                                  "longitude": 127.0557
                                },
                                "imageUrl": "https://example.com/moment.jpg",
                                "rating": {
                                  "average": 4.9,
                                  "count": 12
                                },
                                "timezone": "Asia/Seoul",
                                "openingHours": [
                                  {
                                    "dayOfWeek": "MON",
                                    "opensAt": "09:00",
                                    "closesAt": "20:00",
                                    "closed": false
                                  }
                                ],
                                "version": 2
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/owner/stores/{storeId}"
                            }
                          }
                          """)))
  @ApiResponse(responseCode = "400", description = "입력 형식 또는 조건 오류")
  @ApiResponse(responseCode = "401", description = "인증 필요")
  @ApiResponse(responseCode = "403", description = "권한 부족 또는 인증 연동 미완료")
  @ApiResponse(responseCode = "404", description = "리소스 없음")
  @ApiResponse(responseCode = "409", description = "상태·버전·시간·가격 충돌")
  @PatchMapping(
      value = "/api/v1/owner/stores/{storeId}",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<StoreDetailResponse>> updateStore(
      @PathVariable("storeId") String storeId,
      @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
      @RequestBody UpdateStoreRequest request) {
    return ResponseEntity.ok()
        .body(
            com.bintime.common.dto.ApiResponse.success(
                svc.updateStore(actor.get(), storeId, request)));
  }

  @Operation(
      operationId = "getTimetable",
      summary = "ST-11 · 매장 일별 타임테이블 조회",
      description =
          "주간 UI에서 선택한 날짜를 조회. 배열 예시는 구조 설명용. 일별 최대 500개 예약.\n\n"
              + "- OWNER — 해당 매장 소유자\n\n"
              + "Oracle 영속 데이터와 도메인 규칙을 검증합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd48108a991f06efbee9ea2"))
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
                                "date": "2026-10-16",
                                "timezone": "Asia/Seoul",
                                "summary": {
                                  "total": 4,
                                  "remainingConfirmed": 2
                                },
                                "resources": [
                                  {
                                    "id": "staff_01",
                                    "label": "담당자 1"
                                  }
                                ],
                                "reservations": [
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
                                    ],
                                    "customer": {
                                      "id": "usr_001",
                                      "displayName": "빈틈고객"
                                    },
                                    "resourceId": "staff_01"
                                  }
                                ],
                                "availableSlots": [
                                  {
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
                                  }
                                ],
                                "vacancies": [
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
                                ]
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/owner/stores/{storeId}/timetable"
                            }
                          }
                          """)))
  @ApiResponse(responseCode = "400", description = "입력 형식 또는 조건 오류")
  @ApiResponse(responseCode = "401", description = "인증 필요")
  @ApiResponse(responseCode = "403", description = "권한 부족 또는 인증 연동 미완료")
  @ApiResponse(responseCode = "404", description = "리소스 없음")
  @ApiResponse(responseCode = "409", description = "상태·버전·시간·가격 충돌")
  @GetMapping(
      value = "/api/v1/owner/stores/{storeId}/timetable",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<TimetableResponse>> getTimetable(
      @PathVariable("storeId") String storeId,
      @RequestParam(value = "date", required = true) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
    return ResponseEntity.ok()
        .body(
            com.bintime.common.dto.ApiResponse.success(svc.timetable(actor.get(), storeId, date)));
  }

  @Operation(
      operationId = "updateService",
      summary = "ST-17 · 시술 정보 수정",
      description =
          "name/basePrice/active 수정. durationMinutes 변경 제외. 기존 예약 가격 스냅샷 유지.\n\n"
              + "- OWNER — 해당 매장 소유자\n"
              + "- X-CSRF-Token 필수\n\n"
              + "Oracle 영속 데이터와 도메인 규칙을 검증합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd48183a811dd12c2cac293"))
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
                                "id": "svc_nail_01",
                                "storeId": "shop_moment",
                                "category": "NAIL",
                                "name": "원컬러 젤 네일",
                                "durationMinutes": 60,
                                "basePrice": 55000,
                                "currency": "KRW",
                                "active": true,
                                "version": 2
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/owner/stores/{storeId}/services/{serviceId}"
                            }
                          }
                          """)))
  @ApiResponse(responseCode = "400", description = "입력 형식 또는 조건 오류")
  @ApiResponse(responseCode = "401", description = "인증 필요")
  @ApiResponse(responseCode = "403", description = "권한 부족 또는 인증 연동 미완료")
  @ApiResponse(responseCode = "404", description = "리소스 없음")
  @ApiResponse(responseCode = "409", description = "상태·버전·시간·가격 충돌")
  @PatchMapping(
      value = "/api/v1/owner/stores/{storeId}/services/{serviceId}",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<StoreServiceResponse>> updateService(
      @PathVariable("storeId") String storeId,
      @PathVariable("serviceId") String serviceId,
      @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
      @RequestBody UpdateStoreServiceRequest request) {
    return ResponseEntity.ok()
        .body(
            com.bintime.common.dto.ApiResponse.success(
                svc.updateService(actor.get(), storeId, serviceId, request)));
  }

  @Operation(
      operationId = "createStore",
      summary = "ST-18 · 매장 등록",
      description = "OWNER 인증 및 Idempotency-Key 필수. 성공 응답은 24시간 재사용합니다.")
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "201", description = "생성 완료", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "400", description = "입력 오류")
  @ApiResponse(responseCode = "401", description = "인증 필요")
  @ApiResponse(responseCode = "403", description = "매장 소유자 권한 필요")
  @ApiResponse(responseCode = "409", description = "멱등 키 또는 시간 충돌")
  @org.springframework.web.bind.annotation.PostMapping(
      value = "/api/v1/owner/stores",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<StoreDetailResponse>> createStore(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody com.bintime.store.dto.CreateStoreRequest request) {
    return ResponseEntity.status(201)
        .body(
            com.bintime.common.dto.ApiResponse.success(
                svc.createStore(actor.get(), idempotencyKey, request)));
  }

  @Operation(
      operationId = "createService",
      summary = "ST-19 · 시술 등록",
      description = "OWNER 인증 및 Idempotency-Key 필수. 성공 응답은 24시간 재사용합니다.")
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "201", description = "생성 완료", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "400", description = "입력 오류")
  @ApiResponse(responseCode = "401", description = "인증 필요")
  @ApiResponse(responseCode = "403", description = "매장 소유자 권한 필요")
  @ApiResponse(responseCode = "409", description = "멱등 키 또는 시간 충돌")
  @ApiResponse(responseCode = "404", description = "매장 또는 시술 없음")
  @org.springframework.web.bind.annotation.PostMapping(
      value = "/api/v1/owner/stores/{storeId}/services",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<StoreServiceResponse>> createService(
      @PathVariable("storeId") String storeId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody com.bintime.store.dto.CreateStoreServiceRequest request) {
    return ResponseEntity.status(201)
        .body(
            com.bintime.common.dto.ApiResponse.success(
                svc.createService(actor.get(), storeId, idempotencyKey, request)));
  }

  @Operation(
      operationId = "createSlot",
      summary = "ST-20 · 일반 슬롯 등록",
      description = "OWNER 인증 및 Idempotency-Key 필수. 성공 응답은 24시간 재사용합니다.")
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "201", description = "생성 완료", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "400", description = "입력 오류")
  @ApiResponse(responseCode = "401", description = "인증 필요")
  @ApiResponse(responseCode = "403", description = "매장 소유자 권한 필요")
  @ApiResponse(responseCode = "409", description = "멱등 키 또는 시간 충돌")
  @ApiResponse(responseCode = "404", description = "매장 또는 시술 없음")
  @org.springframework.web.bind.annotation.PostMapping(
      value = "/api/v1/owner/stores/{storeId}/slots",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<ReservationSlotResponse>> createSlot(
      @PathVariable("storeId") String storeId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody com.bintime.store.dto.CreateSlotRequest request) {
    return ResponseEntity.status(201)
        .body(
            com.bintime.common.dto.ApiResponse.success(
                svc.createSlot(actor.get(), storeId, idempotencyKey, request)));
  }
}
