package com.bintime.search.controller;

import com.bintime.search.dto.CancelWaitlistRequest;
import com.bintime.search.dto.CreateWaitlistRequest;
import com.bintime.search.dto.UpdateWaitlistRequest;
import com.bintime.search.dto.WaitlistListResponse;
import com.bintime.search.dto.WaitlistResponse;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "search")
public class WaitlistController {

  // 실제 처리 전까지 성공으로 오인되지 않도록 모든 요청에 501을 반환한다.
  @Operation(
      operationId = "createWaitlist",
      summary = "S-03 · 빈자리 대기 등록",
      description =
          "Idempotency-Key 필수. 특정 매장 또는 지역·카테고리 조건. 동일 ACTIVE 조건은 409 DUPLICATE_WAITLIST. 1인 ACTIVE"
              + " 최대 20개.\n\n"
              + "- CUSTOMER — 본인 데이터\n"
              + "- X-CSRF-Token 필수\n"
              + "- Idempotency-Key 필수 (24시간 재생)\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481b491eafe6165c139c9"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "201", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @PostMapping(
      value = "/api/v1/search/waitlists",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<WaitlistResponse>> createWaitlist(
      @RequestHeader("X-CSRF-Token") String csrfToken,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody CreateWaitlistRequest request) {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }

  @Operation(
      operationId = "getMyWaitlists",
      summary = "S-04 · 내 대기 목록 조회",
      description =
          "status 기본 ACTIVE. 후보를 store 원장에서 재검증. 제안은 선점 아님.\n\n"
              + "- CUSTOMER — 본인 데이터\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481cd9cf8d1076661e3cf"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @GetMapping(value = "/api/v1/search/waitlists/me", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<WaitlistListResponse>> getMyWaitlists(
      @io.swagger.v3.oas.annotations.Parameter(
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      allowableValues = {"ACTIVE", "FULFILLED", "CANCELED", "EXPIRED"}))
          @RequestParam(value = "status", required = false, defaultValue = "ACTIVE")
          String status,
      @RequestParam(value = "cursor", required = false) String cursor,
      @io.swagger.v3.oas.annotations.Parameter(
              schema = @io.swagger.v3.oas.annotations.media.Schema(minimum = "1", maximum = "100"))
          @RequestParam(value = "limit", required = false, defaultValue = "20")
          Integer limit) {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }

  @Operation(
      operationId = "getWaitlist",
      summary = "S-05 · 대기 상세·예약 후보 조회",
      description =
          "최신 예약 가능 Offer 최대 3개를 startAt 오름차순 반환. 상태와 availability를 구분.\n\n"
              + "- CUSTOMER — 본인 데이터\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd4817c935cd20b6950f87a"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @GetMapping(
      value = "/api/v1/search/waitlists/{waitlistId}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<WaitlistResponse>> getWaitlist(
      @PathVariable("waitlistId") String waitlistId) {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }

  @Operation(
      operationId = "updateWaitlist",
      summary = "S-06 · 대기 조건·희망 시간 변경",
      description =
          "conditions 전체 교체. ACTIVE만 수정. 이전 Offer 무효화 후 재매칭.\n\n"
              + "- CUSTOMER — 본인 데이터\n"
              + "- X-CSRF-Token 필수\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481959feaea05c12928b2"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @PatchMapping(
      value = "/api/v1/search/waitlists/{waitlistId}",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<WaitlistResponse>> updateWaitlist(
      @PathVariable("waitlistId") String waitlistId,
      @RequestHeader("X-CSRF-Token") String csrfToken,
      @RequestBody UpdateWaitlistRequest request) {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }

  @Operation(
      operationId = "cancelWaitlist",
      summary = "S-07 · 대기 취소",
      description =
          "Idempotency-Key 필수. 본인 ACTIVE 대기만 취소. 이미 취소된 상태는 200.\n\n"
              + "- CUSTOMER — 본인 데이터\n"
              + "- X-CSRF-Token 필수\n"
              + "- Idempotency-Key 필수 (24시간 재생)\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd48168ac03e08aa5df488c"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @PostMapping(
      value = "/api/v1/search/waitlists/{waitlistId}/cancel",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<WaitlistResponse>> cancelWaitlist(
      @PathVariable("waitlistId") String waitlistId,
      @RequestHeader("X-CSRF-Token") String csrfToken,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody CancelWaitlistRequest request) {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }
}
