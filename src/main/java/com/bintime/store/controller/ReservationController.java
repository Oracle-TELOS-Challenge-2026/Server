package com.bintime.store.controller;

import com.bintime.store.dto.CancelReservationRequest;
import com.bintime.store.dto.CancelReservationResponse;
import com.bintime.store.dto.CreateReservationRequest;
import com.bintime.store.dto.ReservationListResponse;
import com.bintime.store.dto.ReservationResponse;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "store")
public class ReservationController {

  // 실제 처리 전까지 성공으로 오인되지 않도록 모든 요청에 501을 반환한다.
  @Operation(
      operationId = "createReservation",
      summary = "ST-06 · 예약 확정",
      description =
          "Idempotency-Key 필수. 자원 시간·고객 시간·대기를 잠금 처리. 409"
              + " SLOT_UNAVAILABLE/PRICE_CHANGED/CUSTOMER_TIME_CONFLICT.\n\n"
              + "- CUSTOMER — 본인 데이터\n"
              + "- X-CSRF-Token 필수\n"
              + "- Idempotency-Key 필수 (24시간 재생)\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481278f63f09125038da1"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "201", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @PostMapping(
      value = "/api/v1/reservations",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<ReservationResponse>> createReservation(
      @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody CreateReservationRequest request) {
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }

  @Operation(
      operationId = "getMyReservations",
      summary = "ST-07 · 내 예약 내역 조회",
      description =
          "다가오는/지난/취소 목록. counts는 전체 기준이며 예시 목록은 1개 행을 보여준다.\n\n"
              + "- CUSTOMER — 본인 데이터\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd4817b813ad3029a4c241c"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
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
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }

  @Operation(
      operationId = "getReservation",
      summary = "ST-08 · 예약 상세 조회",
      description =
          "본인 또는 해당 매장 사장님만 접근. OWNER는 customer·resourceId 확장 필드를 추가 반환.\n\n"
              + "- 예약 본인 또는 해당 매장 OWNER\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481ec9983fd64ba822751"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @GetMapping(
      value = "/api/v1/reservations/{reservationId}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<ReservationResponse>> getReservation(
      @PathVariable("reservationId") String reservationId) {
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }

  @Operation(
      operationId = "cancelReservation",
      summary = "ST-09 · 예약 취소 및 빈자리 생성",
      description =
          "Idempotency-Key 필수. CONFIRMED→CANCELED. 원예약당 Vacancy 최대 1개. 종료한 예약은 vacancy=null.\n\n"
              + "- 예약 본인 또는 해당 매장 OWNER\n"
              + "- X-CSRF-Token 필수\n"
              + "- Idempotency-Key 필수 (24시간 재생)\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd48174b700df232299fa77"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
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
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }
}
