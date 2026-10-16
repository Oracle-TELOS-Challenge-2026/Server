package com.bintime.user.service;

import com.bintime.user.dto.SaveLocationRequest;
import com.bintime.user.dto.SavePreferencesRequest;
import com.bintime.user.dto.UpdateProfileRequest;
import com.bintime.user.dto.UserLocationResponse;
import com.bintime.user.dto.UserPreferencesResponse;
import com.bintime.user.dto.UserProfileResponse;
import com.bintime.user.dto.UserProfileResponse.Preferences;
import com.bintime.user.repository.UserProfileRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!api-docs")
public class UserProfileService {
  private final UserProfileRepository profiles;

  public UserProfileService(UserProfileRepository profiles) {
    this.profiles = profiles;
  }

  @Transactional(readOnly = true)
  public UserProfileResponse getProfile(String userId) {
    return profiles.findProfile(userId).orElseThrow(UserProfileService::unauthorized);
  }

  @Transactional
  public UserProfileResponse updateProfile(String userId, UpdateProfileRequest request) {
    String name = request.displayName() == null ? "" : request.displayName().strip();
    if (name.isEmpty() || name.codePointCount(0, name.length()) > 30) {
      throw new UserProfileException(
          HttpStatus.BAD_REQUEST, "BAD_REQUEST", "표시 이름은 앞뒤 공백을 제거한 후 1~30자여야 합니다.");
    }
    if (profiles.updateDisplayName(userId, name, request.expectedVersion()) == 0) {
      throw conflict(userId, "VERSION_CONFLICT", "사용자 정보가 변경되었습니다. 다시 조회해 주세요.");
    }
    // UPDATE의 행 잠금을 응답 조회까지 유지해, 다른 요청의 변경 결과를 돌려주지 않는다.
    return getProfile(userId);
  }

  @Transactional
  public UserLocationResponse saveLocation(String userId, SaveLocationRequest request) {
    // DB TIMESTAMP(6)와 정밀도를 맞추며, 같은 관측 시각의 요청은 갱신을 허용한다.
    OffsetDateTime observedAt = request.observedAt().truncatedTo(ChronoUnit.MICROS);
    OffsetDateTime savedAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    if (profiles.saveLocation(userId, request, observedAt, savedAt) == 0) {
      throw conflict(userId, "STALE_LOCATION", "저장된 위치보다 오래된 관측 시각입니다.");
    }
    // 위치 갱신은 별도 관측 시각으로 경합을 제어하므로 프로필·선호도 버전을 바꾸지 않는다.
    return getProfile(userId).location();
  }

  @Transactional
  public void deleteLocation(String userId) {
    if (profiles.deleteLocation(userId) == 0) {
      throw unauthorized();
    }
  }

  @Transactional
  public UserPreferencesResponse savePreferences(String userId, SavePreferencesRequest request) {
    // text를 보내지 않는 기존 클라이언트가 저장된 자유 입력을 지우지 않도록 유지한다.
    String text = request.text();
    if (text == null) {
      UserProfileResponse current = getProfile(userId);
      if (!current.version().equals(request.expectedVersion())) {
        throw conflict(userId, "VERSION_CONFLICT", "사용자 정보가 변경되었습니다. 다시 조회해 주세요.");
      }
      text = current.preferences().text();
    }
    Preferences preferences =
        new Preferences(request.categories(), request.keywords(), request.maxPrice(), text);
    if (profiles.updatePreferences(userId, preferences, request.expectedVersion()) == 0) {
      throw conflict(userId, "VERSION_CONFLICT", "사용자 정보가 변경되었습니다. 다시 조회해 주세요.");
    }
    UserProfileResponse profile = getProfile(userId);
    return new UserPreferencesResponse(
        profile.preferences().categories(),
        profile.preferences().keywords(),
        profile.preferences().maxPrice(),
        profile.preferences().text(),
        profile.version());
  }

  private UserProfileException conflict(String userId, String code, String message) {
    if (!profiles.exists(userId)) {
      return unauthorized();
    }
    return new UserProfileException(HttpStatus.CONFLICT, code, message);
  }

  private static UserProfileException unauthorized() {
    return new UserProfileException(
        HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인한 사용자 정보를 찾을 수 없습니다.");
  }
}
