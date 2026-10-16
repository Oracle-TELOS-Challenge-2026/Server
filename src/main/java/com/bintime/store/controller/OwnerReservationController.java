package com.bintime.store.controller;

import com.bintime.store.dto.OwnerReservationResponse;
import com.bintime.store.dto.UpdateReservationStatusRequest;
import com.bintime.store.dto.UpdateReservationStatusResponse;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "store")
public class OwnerReservationController {

  // 실제 처리 전까지 성공으로 오인되지 않도록 모든 요청에 501을 반환한다.
  @Operation(
      operationId = "getOwnerReservation",
      summary = "ST-12 · 사장님 예약 상세 조회",
      description =
          "본인 매장 예약만 조회. 고객 표시 이름과 자원 ID 포함.\n\n"
              + "- OWNER — 해당 매장 소유자\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd4817c8755dc27b321fa8c"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @GetMapping(
      value = "/api/v1/owner/stores/{storeId}/reservations/{reservationId}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<OwnerReservationResponse>>
      getReservation(
          @PathVariable("storeId") String storeId,
          @PathVariable("reservationId") String reservationId) {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }

  @Operation(
      operationId = "updateReservationStatus",
      summary = "ST-13 · 시술 완료·노쇼 처리",
      description =
          "Idempotency-Key 필수. COMPLETED/NO_SHOW만 허용. 완료는 종료 이후, 노쇼는 시작+10분 이후. 노쇼의 종료 전 남은 구간이면"
              + " vacancy 생성.\n\n"
              + "- OWNER — 해당 매장 소유자\n"
              + "- X-CSRF-Token 필수\n"
              + "- Idempotency-Key 필수 (24시간 재생)\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481d7a2e4fba29a1d7554"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @PostMapping(
      value = "/api/v1/owner/stores/{storeId}/reservations/{reservationId}/status",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<UpdateReservationStatusResponse>>
      updateReservationStatus(
          @PathVariable("storeId") String storeId,
          @PathVariable("reservationId") String reservationId,
          @RequestHeader("X-CSRF-Token") String csrfToken,
          @RequestHeader("Idempotency-Key") String idempotencyKey,
          @RequestBody UpdateReservationStatusRequest request) {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }
}
