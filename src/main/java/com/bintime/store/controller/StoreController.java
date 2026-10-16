package com.bintime.store.controller;

import com.bintime.store.dto.CategoryListResponse;
import com.bintime.store.dto.ReservationSlotListResponse;
import com.bintime.store.dto.ReservationSlotResponse;
import com.bintime.store.dto.StoreDetailResponse;
import com.bintime.store.dto.StoreServiceListResponse;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "store")
public class StoreController {

  // 실제 처리 전까지 성공으로 오인되지 않도록 모든 요청에 501을 반환한다.
  @Operation(
      operationId = "getCategories",
      summary = "ST-01 · 시술 카테고리 조회",
      description =
          "고정 목록이며 페이지네이션 없음.\n\n"
              + "- 공개 API (OAuth 콜백은 state 검증)\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481d581b5ce3d62ce1c3e"))
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @GetMapping(value = "/api/v1/stores/categories", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<CategoryListResponse>> getCategories() {

    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }

  @Operation(
      operationId = "getStore",
      summary = "ST-02 · 매장 상세 조회",
      description =
          "매장 소개·주소·좌표·이미지·평점 요약·영업시간. 초기 데이터 예시.\n\n"
              + "- 공개 API (OAuth 콜백은 state 검증)\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd4817ea5c4e613c28f0b7a"))
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @GetMapping(value = "/api/v1/stores/{storeId}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<StoreDetailResponse>> getStore(
      @PathVariable("storeId") String storeId) {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }

  @Operation(
      operationId = "getServices",
      summary = "ST-03 · 매장 시술 목록 조회",
      description =
          "시술명·소요시간·정가·활성 여부.\n\n"
              + "- 공개 API (OAuth 콜백은 state 검증)\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd48116b811ca85ed7225b1"))
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
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
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }

  @Operation(
      operationId = "getAvailableSlots",
      summary = "ST-04 · 예약 가능 시간 조회",
      description =
          "from/to는 날짜, 양끝 포함 최대 31일. 공개된 미래 AVAILABLE 슬롯만 조회.\n\n"
              + "- 공개 API (OAuth 콜백은 state 검증)\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd48115b8d0d7fbaa43a726"))
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
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
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }

  @Operation(
      operationId = "getSlot",
      summary = "ST-05 · 슬롯 최신 상태 조회",
      description =
          "예약 직전에 가격·version·bookable 재확인. BOOKED는 예약자 정보 없이 반환.\n\n"
              + "- 공개 API (OAuth 콜백은 state 검증)\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd4817c8bb8ca01f9db0058"))
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @GetMapping(
      value = "/api/v1/stores/{storeId}/slots/{slotId}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<ReservationSlotResponse>> getSlot(
      @PathVariable("storeId") String storeId, @PathVariable("slotId") String slotId) {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }
}
