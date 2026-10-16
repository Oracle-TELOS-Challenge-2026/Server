package com.bintime.user.controller;

import com.bintime.user.dto.SaveLocationRequest;
import com.bintime.user.dto.SavePreferencesRequest;
import com.bintime.user.dto.UpdateProfileRequest;
import com.bintime.user.dto.UserLocationResponse;
import com.bintime.user.dto.UserPreferencesResponse;
import com.bintime.user.dto.UserProfileResponse;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "user")
public class UserController {

  // 실제 처리 전까지 성공으로 오인되지 않도록 모든 요청에 501을 반환한다.
  @Operation(
      operationId = "getMyProfile",
      summary = "U-04 · 내 프로필 조회",
      description =
          "최근 위치·선호도·허용 역할·관리 가능한 매장을 조회한다.\n\n"
              + "- 로그인 세션 — 본인 데이터\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481da8f4dd06507d4f48e"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(
      responseCode = "200",
      description = "명세상 성공 응답 (미구현)",
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
                                  "maxPrice": 60000
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
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @GetMapping(value = "/api/v1/users/me", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<UserProfileResponse>> getMyProfile() {

    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }

  @Operation(
      operationId = "updateMyProfile",
      summary = "U-05 · 내 프로필 수정",
      description =
          "displayName 1\\~30자. expectedVersion 불일치 시 409 VERSION_CONFLICT.\n\n"
              + "- 로그인 세션 — 본인 데이터\n"
              + "- X-CSRF-Token 필수\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481a7b8f6e7e95f7578bc"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(
      responseCode = "200",
      description = "명세상 성공 응답 (미구현)",
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
                                  "maxPrice": 60000
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
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @PatchMapping(
      value = "/api/v1/users/me",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<UserProfileResponse>> updateMyProfile(
      @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
      @RequestBody UpdateProfileRequest request) {
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }

  @Operation(
      operationId = "saveMyLocation",
      summary = "U-06 · 최근 위치 저장",
      description =
          "더 오래된 관측 시각이면 409 STALE_LOCATION. 위치 동의는 브라우저에서 받는다.\n\n"
              + "- 로그인 세션 — 본인 데이터\n"
              + "- X-CSRF-Token 필수\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481518e1ac2ed5c1a795b"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(
      responseCode = "200",
      description = "명세상 성공 응답 (미구현)",
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
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @PutMapping(
      value = "/api/v1/users/me/location",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<UserLocationResponse>> saveMyLocation(
      @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
      @RequestBody SaveLocationRequest request) {
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }

  @Operation(
      operationId = "deleteMyLocation",
      summary = "U-07 · 최근 위치 삭제",
      description =
          "기존 대기의 희망 지역은 변경하지 않는다.\n\n"
              + "- 로그인 세션 — 본인 데이터\n"
              + "- X-CSRF-Token 필수\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd48122aa2cf4ab0b45c569"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "204", description = "204 No Content — 응답 본문 없음", content = @Content)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @DeleteMapping(value = "/api/v1/users/me/location")
  public ResponseEntity<Void> deleteMyLocation(
      @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken) {
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }

  @Operation(
      operationId = "saveMyPreferences",
      summary = "U-08 · 선호도 저장",
      description =
          "PUT 전체 교체. categories·keywords·maxPrice·expectedVersion 필수.\n\n"
              + "- 로그인 세션 — 본인 데이터\n"
              + "- X-CSRF-Token 필수\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd4811b914ee86f66a91271"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(
      responseCode = "200",
      description = "명세상 성공 응답 (미구현)",
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
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @PutMapping(
      value = "/api/v1/users/me/preferences",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<UserPreferencesResponse>>
      saveMyPreferences(
          @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
          @RequestBody SavePreferencesRequest request) {
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }
}
