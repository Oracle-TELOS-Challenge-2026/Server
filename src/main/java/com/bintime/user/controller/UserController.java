package com.bintime.user.controller;

import com.bintime.common.dto.ApiError;
import com.bintime.common.dto.ApiErrorResponse;
import com.bintime.common.dto.ResponseMeta;
import com.bintime.user.dto.SaveLocationRequest;
import com.bintime.user.dto.SavePreferencesRequest;
import com.bintime.user.dto.UpdateProfileRequest;
import com.bintime.user.dto.UserLocationResponse;
import com.bintime.user.dto.UserPreferencesResponse;
import com.bintime.user.dto.UserProfileResponse;
import com.bintime.user.security.BintimePrincipal;
import com.bintime.user.service.UserProfileException;
import com.bintime.user.service.UserProfileService;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "user")
public class UserController {

  private final ObjectProvider<UserProfileService> profiles;

  public UserController(ObjectProvider<UserProfileService> profiles) {
    this.profiles = profiles;
  }

  // DB 없이 문서를 여는 api-docs 프로필에서는 실행을 제공하지 않는다.
  private UserProfileService profileService() {
    UserProfileService service = profiles.getIfAvailable();
    if (service == null) {
      throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    }
    return service;
  }

  private String userId(BintimePrincipal principal) {
    if (principal == null) {
      throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    return principal.userId();
  }

  @ExceptionHandler(UserProfileException.class)
  ResponseEntity<ApiErrorResponse> handleProfileError(
      UserProfileException error, HttpServletRequest request) {
    return ResponseEntity.status(error.status())
        .body(
            new ApiErrorResponse(
                new ApiError(error.code(), error.getMessage(), List.of(), false),
                ResponseMeta.now(request.getRequestURI())));
  }

  @Operation(
      operationId = "getMyProfile",
      summary = "U-04 · 내 프로필 조회",
      description =
          "최근 위치·선호도·허용 역할·관리 가능한 매장을 조회한다. 최상위 version은 이름·선호도가 공유하는 사용자 버전이며 "
              + "U-05·U-08의 expectedVersion으로 보낸다. preferences.text는 저장한 취향 원문이며 없으면 빈 문자열이다.\n\n"
              + "- 로그인 세션 — 본인 데이터\n\n"
              + "세션의 사용자 ID로 본인 데이터만 처리합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481da8f4dd06507d4f48e"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(
      responseCode = "200",
      description = "처리 성공",
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
                                "id": "usr_001",
                                "displayName": "빈틈고객",
                                "roles": [
                                  "CUSTOMER"
                                ],
                                "ownedStoreIds": [],
                                "location": {
                                  "latitude": 37.5445,
                                  "longitude": 127.0557,
                                  "accuracyMeters": 30,
                                  "source": "DEVICE",
                                  "observedAt": "2026-10-16T14:00:00+09:00",
                                  "savedAt": "2026-10-16T14:00:02+09:00"
                                },
                                "preferences": {
                                  "categories": [
                                    "NAIL"
                                  ],
                                  "keywords": [
                                    "심플",
                                    "원컬러"
                                  ],
                                  "maxPrice": 60000,
                                  "text": "심플한 원컬러 네일을 선호해요."
                                },
                                "version": 1
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/users/me"
                            }
                          }
                          """)))
  @ApiResponse(
      responseCode = "401",
      description = "UNAUTHORIZED: 로그인 필요 또는 사용자 없음",
      content = @Content)
  @GetMapping(value = "/api/v1/users/me", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<UserProfileResponse>> getMyProfile(
      @AuthenticationPrincipal BintimePrincipal principal) {
    return ResponseEntity.ok(
        com.bintime.common.dto.ApiResponse.success(profileService().getProfile(userId(principal))));
  }

  @Operation(
      operationId = "updateMyProfile",
      summary = "U-05 · 내 프로필 수정",
      description =
          "displayName 1\\~30자. expectedVersion은 U-04의 최상위 version을 사용한다. 이름·선호도가 버전을 공유하며 "
              + "불일치 시 409 VERSION_CONFLICT.\n\n"
              + "- 로그인 세션 — 본인 데이터\n"
              + "- X-CSRF-Token 필수\n\n"
              + "세션의 사용자 ID로 본인 데이터만 처리합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481a7b8f6e7e95f7578bc"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(
      responseCode = "200",
      description = "처리 성공",
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
                                "id": "usr_001",
                                "displayName": "빈틈고객",
                                "roles": [
                                  "CUSTOMER"
                                ],
                                "ownedStoreIds": [],
                                "location": {
                                  "latitude": 37.5445,
                                  "longitude": 127.0557,
                                  "accuracyMeters": 30,
                                  "source": "DEVICE",
                                  "observedAt": "2026-10-16T14:00:00+09:00",
                                  "savedAt": "2026-10-16T14:00:02+09:00"
                                },
                                "preferences": {
                                  "categories": [
                                    "NAIL"
                                  ],
                                  "keywords": [
                                    "심플",
                                    "원컬러"
                                  ],
                                  "maxPrice": 60000,
                                  "text": "심플한 원컬러 네일을 선호해요."
                                },
                                "version": 2
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/users/me"
                            }
                          }
                          """)))
  @ApiResponse(
      responseCode = "409",
      description = "VERSION_CONFLICT: expectedVersion 불일치",
      content = @Content)
  @ApiResponse(
      responseCode = "401",
      description = "UNAUTHORIZED: 로그인 필요 또는 사용자 없음",
      content = @Content)
  @ApiResponse(
      responseCode = "403",
      description = "CSRF_INVALID: CSRF 토큰 누락 또는 불일치",
      content = @Content)
  @ApiResponse(
      responseCode = "400",
      description = "BAD_REQUEST: 입력값 형식 또는 범위 오류",
      content = @Content)
  @PatchMapping(
      value = "/api/v1/users/me",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<UserProfileResponse>> updateMyProfile(
      @Parameter(description = "현재 세션의 CSRF 토큰. 누락·불일치는 403 CSRF_INVALID", required = true)
          @RequestHeader(value = "X-CSRF-Token", required = false)
          String csrfToken,
      @Valid @RequestBody UpdateProfileRequest request,
      @AuthenticationPrincipal BintimePrincipal principal) {
    return ResponseEntity.ok(
        com.bintime.common.dto.ApiResponse.success(
            profileService().updateProfile(userId(principal), request)));
  }

  @Operation(
      operationId = "saveMyLocation",
      summary = "U-06 · 최근 위치 저장",
      description =
          "더 오래된 관측 시각이면 409 STALE_LOCATION. 동일 시각은 덮어쓴다. 위치는 프로필 version과 별도로 관리한다. 위치 동의는 브라우저에서"
              + " 받는다.\n\n"
              + "- 로그인 세션 — 본인 데이터\n"
              + "- X-CSRF-Token 필수\n\n"
              + "세션의 사용자 ID로 본인 데이터만 처리합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481518e1ac2ed5c1a795b"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(
      responseCode = "200",
      description = "처리 성공",
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
                                "latitude": 37.5445,
                                "longitude": 127.0557,
                                "accuracyMeters": 30,
                                "source": "DEVICE",
                                "observedAt": "2026-10-16T14:00:00+09:00",
                                "savedAt": "2026-10-16T14:20:00+09:00"
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/users/me/location"
                            }
                          }
                          """)))
  @ApiResponse(
      responseCode = "409",
      description = "STALE_LOCATION: 저장된 위치보다 오래된 관측 시각",
      content = @Content)
  @ApiResponse(
      responseCode = "401",
      description = "UNAUTHORIZED: 로그인 필요 또는 사용자 없음",
      content = @Content)
  @ApiResponse(
      responseCode = "403",
      description = "CSRF_INVALID: CSRF 토큰 누락 또는 불일치",
      content = @Content)
  @ApiResponse(
      responseCode = "400",
      description = "BAD_REQUEST: 입력값 형식 또는 범위 오류",
      content = @Content)
  @PutMapping(
      value = "/api/v1/users/me/location",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<UserLocationResponse>> saveMyLocation(
      @Parameter(description = "현재 세션의 CSRF 토큰. 누락·불일치는 403 CSRF_INVALID", required = true)
          @RequestHeader(value = "X-CSRF-Token", required = false)
          String csrfToken,
      @Valid @RequestBody SaveLocationRequest request,
      @AuthenticationPrincipal BintimePrincipal principal) {
    return ResponseEntity.ok(
        com.bintime.common.dto.ApiResponse.success(
            profileService().saveLocation(userId(principal), request)));
  }

  @Operation(
      operationId = "deleteMyLocation",
      summary = "U-07 · 최근 위치 삭제",
      description =
          "기존 대기의 희망 지역은 변경하지 않는다.\n\n"
              + "- 로그인 세션 — 본인 데이터\n"
              + "- X-CSRF-Token 필수\n\n"
              + "세션의 사용자 ID로 본인 데이터만 처리합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd48122aa2cf4ab0b45c569"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "204", description = "204 No Content — 응답 본문 없음", content = @Content)
  @ApiResponse(
      responseCode = "401",
      description = "UNAUTHORIZED: 로그인 필요 또는 사용자 없음",
      content = @Content)
  @ApiResponse(
      responseCode = "403",
      description = "CSRF_INVALID: CSRF 토큰 누락 또는 불일치",
      content = @Content)
  @DeleteMapping(value = "/api/v1/users/me/location")
  public ResponseEntity<Void> deleteMyLocation(
      @Parameter(description = "현재 세션의 CSRF 토큰. 누락·불일치는 403 CSRF_INVALID", required = true)
          @RequestHeader(value = "X-CSRF-Token", required = false)
          String csrfToken,
      @AuthenticationPrincipal BintimePrincipal principal) {
    profileService().deleteLocation(userId(principal));
    return ResponseEntity.noContent().build();
  }

  @Operation(
      operationId = "saveMyPreferences",
      summary = "U-08 · 선호도 저장",
      description =
          "categories·keywords·maxPrice는 전체 교체하며 expectedVersion과 함께 필수다. maxPrice:null은 상한 없음. "
              + "선택적인 text는 취향 원문 0~2000자이며 빈 문자열은 초기화, 생략은 기존 원문 유지다. 명시적 null이나 "
              + "문자열 외 값은 400이다. AI 분석 없이 원문을 저장한다. U-04의 최상위 version을 expectedVersion으로 "
              + "사용하며 이름·선호도가 공유한다. 버전 불일치는 409 VERSION_CONFLICT.\n\n"
              + "- 로그인 세션 — 본인 데이터\n"
              + "- X-CSRF-Token 필수\n\n"
              + "세션의 사용자 ID로 본인 데이터만 처리합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd4811b914ee86f66a91271"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(
      responseCode = "200",
      description = "처리 성공",
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
                                "categories": [
                                  "NAIL"
                                ],
                                "keywords": [
                                  "심플",
                                  "원컬러"
                                ],
                                "maxPrice": 60000,
                                "text": "심플한 원컬러 네일을 선호해요.",
                                "version": 2
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/users/me/preferences"
                            }
                          }
                          """)))
  @ApiResponse(
      responseCode = "401",
      description = "UNAUTHORIZED: 로그인 필요 또는 사용자 없음",
      content = @Content)
  @ApiResponse(
      responseCode = "403",
      description = "CSRF_INVALID: CSRF 토큰 누락 또는 불일치",
      content = @Content)
  @ApiResponse(
      responseCode = "400",
      description = "BAD_REQUEST: 입력값 형식 또는 범위 오류",
      content = @Content)
  @ApiResponse(
      responseCode = "409",
      description = "VERSION_CONFLICT: expectedVersion 불일치",
      content = @Content)
  @PutMapping(
      value = "/api/v1/users/me/preferences",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<UserPreferencesResponse>>
      saveMyPreferences(
          @Parameter(description = "현재 세션의 CSRF 토큰. 누락·불일치는 403 CSRF_INVALID", required = true)
              @RequestHeader(value = "X-CSRF-Token", required = false)
              String csrfToken,
          @Valid @RequestBody SavePreferencesRequest request,
          @AuthenticationPrincipal BintimePrincipal principal) {
    return ResponseEntity.ok(
        com.bintime.common.dto.ApiResponse.success(
            profileService().savePreferences(userId(principal), request)));
  }
}
