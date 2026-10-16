package com.bintime.search.controller;

import com.bintime.search.dto.CreateMatchJobRequest;
import com.bintime.search.dto.MatchJobResponse;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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

  private final org.springframework.beans.factory.ObjectProvider<
          com.bintime.search.service.MatchService>
      provider;

  public InternalSearchController(
      org.springframework.beans.factory.ObjectProvider<com.bintime.search.service.MatchService>
          provider) {
    this.provider = provider;
  }

  private com.bintime.search.service.MatchService service() {
    var service = provider.getIfAvailable();
    if (service == null) throw com.bintime.search.service.SearchFailure.unavailable();
    return service;
  }

  private String user(jakarta.servlet.http.HttpServletRequest http) {
    return (String) http.getAttribute("search.user");
  }

  @Operation(
      operationId = "createMatchJob",
      summary = "S-08 · 발행 슬롯 매칭 작업 등록 (내부)",
      description =
          "내부 인증만 허용. eventId 중복은 같은 jobId. 필수 조건 일치 후 벡터 순위. 외부 공개 API 아님.\n\n"
              + "- INTERNAL — 외부 접근 불가, 내부 인증 또는 모듈 호출\n\n"
              + "외부 도메인 미연결 또는 외부 장애는 503 (retryable=true)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481c69ccade175948c157"))
  @ApiResponse(
      responseCode = "202",
      description = "성공 응답",
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
                                "jobId": "job_001",
                                "status": "QUEUED"
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/internal/search/match-jobs"
                            }
                          }
                          """)))
  @ApiResponse(
      responseCode = "400",
      description = "잘못된 입력 또는 커서 (retryable=false)",
      content = @Content)
  @ApiResponse(responseCode = "401", description = "인증 필요 (retryable=false)", content = @Content)
  @ApiResponse(
      responseCode = "403",
      description = "권한 또는 CSRF/내부 인증 실패 (retryable=false)",
      content = @Content)
  @ApiResponse(
      responseCode = "404",
      description = "본인 리소스 없음 (retryable=false)",
      content = @Content)
  @ApiResponse(
      responseCode = "503",
      description = "외부 의존 기능 장애. 수동 입력 또는 재시도 가능 (retryable=true)",
      content = @Content)
  @ApiResponse(responseCode = "409", description = "동일 eventId의 다른 내용", content = @Content)
  @PostMapping(
      value = "/api/v1/internal/search/match-jobs",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<MatchJobResponse>> createMatchJob(
      jakarta.servlet.http.HttpServletRequest http, @RequestBody CreateMatchJobRequest request) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(com.bintime.common.dto.ApiResponse.success(service().submit(request)));
  }
}
