package com.bintime.user.service;

import com.bintime.common.web.ApiBusinessException;
import com.bintime.user.contract.UserPreferencesChanged;
import com.bintime.user.entity.UserAccount;
import com.bintime.user.repository.UserAccountRepository;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 선호 원본은 user가 소유하며 search에는 전체 JSON 문자열을 제공한다. */
@Service
@Lazy
@Profile("!api-docs")
public class UserPreferenceService {
  private final UserAccountRepository db;
  private final ApplicationEventPublisher events;
  private final JsonMapper json;

  public UserPreferenceService(
      UserAccountRepository db, ApplicationEventPublisher events, JsonMapper json) {
    this.db = db;
    this.events = events;
    this.json = json;
  }

  @Transactional(readOnly = true)
  public String explicitPreferences(String userId) {
    return require(db.find(userId)).preferences;
  }

  /** 자유로운 JSON 저장도 프로필 API와 같은 변경 이벤트로 벡터를 갱신한다. */
  @Transactional
  public void replace(String userId, String preferencesJson) {
    try {
      if (preferencesJson == null
          || preferencesJson.isBlank()
          || json.readerFor(tools.jackson.databind.JsonNode.class)
                  .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                  .readValue(preferencesJson)
              == null) throw new IllegalArgumentException();
    } catch (Exception e) {
      throw new ApiBusinessException(400, "INVALID_PREFERENCES_JSON");
    }
    var account = require(db.lock(userId));
    if (Objects.equals(account.preferences, preferencesJson)) return;
    account.preferences = preferencesJson;
    // 동기 이벤트 소비자가 outbox를 같은 트랜잭션에 기록한다. 커밋 후 콜백으로 유실될 창을 만들지 않는다.
    events.publishEvent(new UserPreferencesChanged(userId));
  }

  private UserAccount require(UserAccount account) {
    if (account == null) throw new ApiBusinessException(404, "USER_NOT_FOUND");
    return account;
  }
}
