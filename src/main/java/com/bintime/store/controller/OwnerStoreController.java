package com.bintime.store.controller;

import com.bintime.store.dto.StoreDetailResponse;
import com.bintime.store.dto.StoreServiceResponse;
import com.bintime.store.dto.TimetableResponse;
import com.bintime.store.dto.UpdateStoreRequest;
import com.bintime.store.dto.UpdateStoreServiceRequest;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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

  // 실제 처리 전까지 성공으로 오인되지 않도록 모든 요청에 501을 반환한다.
  @Operation(
      operationId = "updateStore",
      summary = "ST-10 · 매장 운영 정보 수정",
      description =
          "description 또는 openingHours 중 하나 이상. 해당 매장 사장님만 변경.\n\n"
              + "- OWNER — 해당 매장 소유자\n"
              + "- X-CSRF-Token 필수\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481aab1edf442cb8b4f32"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @PatchMapping(
      value = "/api/v1/owner/stores/{storeId}",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<StoreDetailResponse>> updateStore(
      @PathVariable("storeId") String storeId,
      @RequestHeader("X-CSRF-Token") String csrfToken,
      @RequestBody UpdateStoreRequest request) {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }

  @Operation(
      operationId = "getTimetable",
      summary = "ST-11 · 매장 일별 타임테이블 조회",
      description =
          "주간 UI에서 선택한 날짜를 조회. 배열 예시는 구조 설명용. 일별 최대 500개 예약.\n\n"
              + "- OWNER — 해당 매장 소유자\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd48108a991f06efbee9ea2"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @GetMapping(
      value = "/api/v1/owner/stores/{storeId}/timetable",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<TimetableResponse>> getTimetable(
      @PathVariable("storeId") String storeId,
      @RequestParam(value = "date", required = true) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }

  @Operation(
      operationId = "updateService",
      summary = "ST-17 · 시술 정보 수정",
      description =
          "name/basePrice/active 수정. durationMinutes 변경 제외. 기존 예약 가격 스냅샷 유지.\n\n"
              + "- OWNER — 해당 매장 소유자\n"
              + "- X-CSRF-Token 필수\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd48183a811dd12c2cac293"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @PatchMapping(
      value = "/api/v1/owner/stores/{storeId}/services/{serviceId}",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<StoreServiceResponse>> updateService(
      @PathVariable("storeId") String storeId,
      @PathVariable("serviceId") String serviceId,
      @RequestHeader("X-CSRF-Token") String csrfToken,
      @RequestBody UpdateStoreServiceRequest request) {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }
}
