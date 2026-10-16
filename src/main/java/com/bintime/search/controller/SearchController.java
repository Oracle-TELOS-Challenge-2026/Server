package com.bintime.search.controller;

import com.bintime.search.dto.AnalyzeIntentRequest;
import com.bintime.search.dto.AnalyzeIntentResponse;
import com.bintime.search.dto.StoreSearchResponse;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "search")
public class SearchController {

  // 실제 처리 전까지 성공으로 오인되지 않도록 모든 요청에 501을 반환한다.
  @Operation(
      operationId = "searchStores",
      summary = "S-01 · 주변 매장·빈자리 검색",
      description =
          "거리·카테고리·시간·가격 기반 검색. 날짜 조건은 시술 전체가 구간 안에 포함. 지도 마커와 같은 결과. limit 최대 50, 검색 커서 유효 5분.\n\n"
              + "- 공개 API (OAuth 콜백은 state 검증)\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd4813b97d4c57041a4e79d"))
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @GetMapping(value = "/api/v1/search/stores", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<StoreSearchResponse>> searchStores(
      @io.swagger.v3.oas.annotations.Parameter(
              schema = @io.swagger.v3.oas.annotations.media.Schema(minimum = "-90", maximum = "90"))
          @RequestParam(value = "latitude", required = true)
          Double latitude,
      @io.swagger.v3.oas.annotations.Parameter(
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(minimum = "-180", maximum = "180"))
          @RequestParam(value = "longitude", required = true)
          Double longitude,
      @io.swagger.v3.oas.annotations.Parameter(
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(minimum = "100", maximum = "10000"))
          @RequestParam(value = "radiusMeters", required = false, defaultValue = "3000")
          Integer radiusMeters,
      @RequestParam(value = "keyword", required = false) String keyword,
      @io.swagger.v3.oas.annotations.Parameter(
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      allowableValues = {"NAIL", "HAIR", "EYELASH", "SKIN"}))
          @RequestParam(value = "category", required = false)
          String category,
      @RequestParam(value = "from", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(value = "to", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to,
      @RequestParam(value = "vacancyOnly", required = false, defaultValue = "false")
          Boolean vacancyOnly,
      @io.swagger.v3.oas.annotations.Parameter(
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      allowableValues = {"DISTANCE", "RECOMMENDED", "PRICE"}))
          @RequestParam(value = "sort", required = false, defaultValue = "DISTANCE")
          String sort,
      @RequestParam(value = "cursor", required = false) String cursor,
      @io.swagger.v3.oas.annotations.Parameter(
              schema = @io.swagger.v3.oas.annotations.media.Schema(minimum = "1", maximum = "50"))
          @RequestParam(value = "limit", required = false, defaultValue = "20")
          Integer limit) {
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }

  @Operation(
      operationId = "analyzeIntent",
      summary = "S-02 · 자연어 예약 조건 분석",
      description =
          "결과 확인 후 별도 S-03 대기 등록. RESOLVED/NEEDS_CLARIFICATION/UNSUPPORTED. AI 장애여도 수동 입력 가능.\n\n"
              + "- CUSTOMER — 본인 데이터\n"
              + "- X-CSRF-Token 필수\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481c99aade960e5397683"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @PostMapping(
      value = "/api/v1/search/intents/analyze",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<AnalyzeIntentResponse>> analyzeIntent(
      @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
      @RequestBody AnalyzeIntentRequest request) {
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }
}
