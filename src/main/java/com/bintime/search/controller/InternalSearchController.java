package com.bintime.search.controller;

import com.bintime.search.dto.CreateMatchJobRequest;
import com.bintime.search.dto.MatchJobResponse;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "search-internal")
public class InternalSearchController {

  // 실제 처리 전까지 성공으로 오인되지 않도록 모든 요청에 501을 반환한다.
  @Operation(
      operationId = "createMatchJob",
      summary = "S-08 · 발행 슬롯 매칭 작업 등록 (내부)",
      description =
          "내부 인증만 허용. eventId 중복은 같은 jobId. 필수 조건 일치 후 벡터 순위. 외부 공개 API 아님.\n\n"
              + "- INTERNAL — 외부 접근 불가, 내부 인증 또는 모듈 호출\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481c69ccade175948c157"))
  @ApiResponse(responseCode = "202", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @PostMapping(
      value = "/api/v1/internal/search/match-jobs",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<MatchJobResponse>> createMatchJob(
      @RequestBody CreateMatchJobRequest request) {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }
}
