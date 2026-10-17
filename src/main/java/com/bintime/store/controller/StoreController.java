package com.bintime.store.controller;

import com.bintime.store.dto.CategoryListResponse;
import com.bintime.store.dto.CustomerTimetableResponse;
import com.bintime.store.dto.ReservationSlotListResponse;
import com.bintime.store.dto.ReservationSlotResponse;
import com.bintime.store.dto.StoreDetailResponse;
import com.bintime.store.dto.StoreServiceListResponse;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "store")
public class StoreController {

  private final com.bintime.store.service.StoreApplicationService svc;
  private final com.bintime.common.auth.CurrentActor actor;

  public StoreController(
      @org.springframework.context.annotation.Lazy
          com.bintime.store.service.StoreApplicationService svc,
      com.bintime.common.auth.CurrentActor actor) {
    this.svc = svc;
    this.actor = actor;
  }

  @Operation(
      operationId = "getCustomerTimetable",
      summary = "ST-22 · 손님용 조회 전용 시간표",
      description =
          "로그인 없이 조회. date는 YYYY-MM-DD, Asia/Seoul 기준. 확정 예약의 시간만 busyTimes에 반환하고 취소·노쇼·완료·미확정 요청은"
              + " 제외한다. availableTimes는 공개된 미래 AVAILABLE 슬롯 중 활성 시술·영업시간·확정 예약 충돌 조건을 만족하는 구간이다."
              + " 미발행 빈자리는 제외한다. 휴무일은 closed=true, opensAt/closesAt=null, availableTimes=[]. 양쪽 목록에"
              + " 없는 영업시간은 확인 필요이며 빈 시간을 자동 생성하지 않는다. 개인정보·예약/슬롯 ID 없이 반환하며 Cache-Control:"
              + " no-store. 조회로 예약·선점·상태 변경·알림을 만들지 않는다. 일별 최대 500개를 초과하면 409.")
  @ApiResponse(
      responseCode = "200",
      description = "성공. Cache-Control: no-store",
      headers =
          @Header(name = "Cache-Control", schema = @Schema(type = "string", example = "no-store")),
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
                            "date":"2026-10-20","timezone":"Asia/Seoul",
                            "openingHours":{"closed":false,"opensAt":"09:00","closesAt":"18:00"},
                            "busyTimes":[{"startAt":"2026-10-20T10:00:00+09:00","endAt":"2026-10-20T11:00:00+09:00"}],
                            "availableTimes":[{"startAt":"2026-10-20T14:00:00+09:00","endAt":"2026-10-20T15:00:00+09:00"}]
                          }},"error":null,"meta":{"timestamp":"2026-10-17T12:00:00","path":"/api/v1/stores/{storeId}/timetable"}}
                          """)))
  @ApiResponse(responseCode = "400", description = "필수 date 누락·형식 오류 / INVALID_DATE")
  @ApiResponse(responseCode = "404", description = "STORE_NOT_FOUND")
  @ApiResponse(responseCode = "409", description = "TIMETABLE_LIMIT_EXCEEDED")
  @GetMapping(
      value = "/api/v1/stores/{storeId}/timetable",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<CustomerTimetableResponse>>
      getCustomerTimetable(
          @PathVariable String storeId,
          @RequestParam @DateTimeFormat(pattern = "uuuu-MM-dd") LocalDate date) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(com.bintime.common.dto.ApiResponse.success(svc.customerTimetable(storeId, date)));
  }

  @Operation(
      operationId = "getCategories",
      summary = "ST-01 · 시술 카테고리 조회",
      description =
          "고정 목록이며 페이지네이션 없음.\n\n"
              + "- 공개 API (OAuth 콜백은 state 검증)\n\n"
              + "Oracle 영속 데이터와 도메인 규칙을 검증합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481d581b5ce3d62ce1c3e"))
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
                                    "code": "NAIL",
                                    "label": "네일"
                                  },
                                  {
                                    "code": "HAIR",
                                    "label": "헤어"
                                  },
                                  {
                                    "code": "EYELASH",
                                    "label": "속눈썹"
                                  },
                                  {
                                    "code": "SKIN",
                                    "label": "피부관리"
                                  }
                                ]
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/stores/categories"
                            }
                          }
                          """)))
  @GetMapping(value = "/api/v1/stores/categories", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<CategoryListResponse>> getCategories() {

    return ResponseEntity.ok().body(com.bintime.common.dto.ApiResponse.success(svc.categories()));
  }

  @Operation(
      operationId = "getStore",
      summary = "ST-02 · 매장 상세 조회",
      description =
          "매장 소개·주소·좌표·이미지·평점 요약·영업시간. 초기 데이터 예시.\n\n"
              + "- 공개 API (OAuth 콜백은 state 검증)\n\n"
              + "Oracle 영속 데이터와 도메인 규칙을 검증합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd4817ea5c4e613c28f0b7a"))
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
                                "description": "취향을 함께 고민하는 네일 공간",
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
                                  },
                                  {
                                    "dayOfWeek": "TUE",
                                    "opensAt": "09:00",
                                    "closesAt": "20:00",
                                    "closed": false
                                  },
                                  {
                                    "dayOfWeek": "WED",
                                    "opensAt": "09:00",
                                    "closesAt": "20:00",
                                    "closed": false
                                  },
                                  {
                                    "dayOfWeek": "THU",
                                    "opensAt": "09:00",
                                    "closesAt": "20:00",
                                    "closed": false
                                  },
                                  {
                                    "dayOfWeek": "FRI",
                                    "opensAt": "09:00",
                                    "closesAt": "20:00",
                                    "closed": false
                                  },
                                  {
                                    "dayOfWeek": "SAT",
                                    "opensAt": "09:00",
                                    "closesAt": "20:00",
                                    "closed": false
                                  },
                                  {
                                    "dayOfWeek": "SUN",
                                    "opensAt": "09:00",
                                    "closesAt": "20:00",
                                    "closed": false
                                  }
                                ],
                                "version": 1
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/stores/{storeId}"
                            }
                          }
                          """)))
  @ApiResponse(responseCode = "404", description = "리소스 없음")
  @GetMapping(value = "/api/v1/stores/{storeId}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<StoreDetailResponse>> getStore(
      @PathVariable("storeId") String storeId) {
    return ResponseEntity.ok()
        .body(com.bintime.common.dto.ApiResponse.success(svc.getStore(storeId)));
  }

  @Operation(
      operationId = "getServices",
      summary = "ST-03 · 매장 시술 목록 조회",
      description =
          "시술명·소요시간·정가·활성 여부.\n\n"
              + "- 공개 API (OAuth 콜백은 state 검증)\n\n"
              + "Oracle 영속 데이터와 도메인 규칙을 검증합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd48116b811ca85ed7225b1"))
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
                                    "id": "svc_nail_01",
                                    "storeId": "shop_moment",
                                    "category": "NAIL",
                                    "name": "원컬러 젤 네일",
                                    "durationMinutes": 60,
                                    "basePrice": 50000,
                                    "currency": "KRW",
                                    "active": true,
                                    "version": 1
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
                              "path": "/api/v1/stores/{storeId}/services"
                            }
                          }
                          """)))
  @ApiResponse(responseCode = "400", description = "입력 형식 또는 조건 오류")
  @ApiResponse(responseCode = "404", description = "리소스 없음")
  @GetMapping(
      value = "/api/v1/stores/{storeId}/services",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<StoreServiceListResponse>> getServices(
      @PathVariable("storeId") String storeId,
      @RequestParam(value = "cursor", required = false) String cursor,
      @io.swagger.v3.oas.annotations.Parameter(
              schema = @io.swagger.v3.oas.annotations.media.Schema(minimum = "1", maximum = "100"))
          @RequestParam(value = "limit", required = false, defaultValue = "20")
          Integer limit) {
    return ResponseEntity.ok()
        .body(com.bintime.common.dto.ApiResponse.success(svc.services(storeId, cursor, limit)));
  }

  @Operation(
      operationId = "getAvailableSlots",
      summary = "ST-04 · 예약 가능 시간 조회",
      description =
          "from/to는 날짜, 양끝 포함 최대 31일. 공개된 미래 AVAILABLE 슬롯만 조회.\n\n"
              + "- 공개 API (OAuth 콜백은 state 검증)\n\n"
              + "Oracle 영속 데이터와 도메인 규칙을 검증합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd48115b8d0d7fbaa43a726"))
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
                                    "id": "slot_1700",
                                    "storeId": "shop_moment",
                                    "serviceId": "svc_nail_01",
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
                                "page": {
                                  "nextCursor": null,
                                  "hasNext": false
                                }
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/stores/{storeId}/slots"
                            }
                          }
                          """)))
  @ApiResponse(responseCode = "400", description = "입력 형식 또는 조건 오류")
  @ApiResponse(responseCode = "404", description = "리소스 없음")
  @GetMapping(value = "/api/v1/stores/{storeId}/slots", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<ReservationSlotListResponse>>
      getAvailableSlots(
          @PathVariable("storeId") String storeId,
          @RequestParam(value = "from", required = true)
              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
              LocalDate from,
          @RequestParam(value = "to", required = true)
              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
              LocalDate to,
          @RequestParam(value = "serviceId", required = false) String serviceId,
          @RequestParam(value = "cursor", required = false) String cursor,
          @io.swagger.v3.oas.annotations.Parameter(
                  schema =
                      @io.swagger.v3.oas.annotations.media.Schema(minimum = "1", maximum = "100"))
              @RequestParam(value = "limit", required = false, defaultValue = "20")
              Integer limit) {
    return ResponseEntity.ok()
        .body(
            com.bintime.common.dto.ApiResponse.success(
                svc.slots(storeId, from, to, serviceId, cursor, limit)));
  }

  @Operation(
      operationId = "getSlot",
      summary = "ST-05 · 슬롯 최신 상태 조회",
      description =
          "예약 직전에 가격·version·bookable 재확인. BOOKED는 예약자 정보 없이 반환.\n\n"
              + "- 공개 API (OAuth 콜백은 state 검증)\n\n"
              + "Oracle 영속 데이터와 도메인 규칙을 검증합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd4817c8bb8ca01f9db0058"))
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
                                "id": "slot_1700",
                                "storeId": "shop_moment",
                                "serviceId": "svc_nail_01",
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
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/stores/{storeId}/slots/{slotId}"
                            }
                          }
                          """)))
  @ApiResponse(responseCode = "404", description = "리소스 없음")
  @GetMapping(
      value = "/api/v1/stores/{storeId}/slots/{slotId}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<ReservationSlotResponse>> getSlot(
      @PathVariable("storeId") String storeId, @PathVariable("slotId") String slotId) {
    return ResponseEntity.ok()
        .body(com.bintime.common.dto.ApiResponse.success(svc.getSlot(storeId, slotId)));
  }
}
