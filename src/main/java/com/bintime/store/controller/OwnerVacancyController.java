package com.bintime.store.controller;

import com.bintime.store.dto.PublishVacancyRequest;
import com.bintime.store.dto.PublishVacancyResponse;
import com.bintime.store.dto.VacancyListResponse;
import com.bintime.store.dto.WithdrawVacancyRequest;
import com.bintime.store.dto.WithdrawVacancyResponse;
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
public class OwnerVacancyController {

  // 실제 처리 전까지 성공으로 오인되지 않도록 모든 요청에 501을 반환한다.
  @Operation(
      operationId = "getVacancies",
      summary = "ST-14 · 취소·노쇼 빈자리 목록 조회",
      description =
          "occurredAt 내림차순. 미발행 DRAFT와 발행 완료 상태를 구분한다.\n\n"
              + "- OWNER — 해당 매장 소유자\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481a49fc5db03d18be940"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
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
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
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
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481ae89e8eb205f157f7e"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
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
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }

  @Operation(
      operationId = "withdrawVacancy",
      summary = "ST-16 · 발행한 빈자리 철회",
      description =
          "Idempotency-Key 필수. 이미 예약된 경우 409 VACANCY_ALREADY_FILLED.\n\n"
              + "- OWNER — 해당 매장 소유자\n"
              + "- X-CSRF-Token 필수\n"
              + "- Idempotency-Key 필수 (24시간 재생)\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481799276c9f05e1a32ec"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
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
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }
}
