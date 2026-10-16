package com.bintime.search.service;

import com.bintime.search.model.EmbeddingVector;
import com.bintime.store.contract.SearchStoreCatalog.Slot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** 알림 접수와 실제 전달에 같은 선호 기준을 적용한다. 점수는 예약 확률이 아니다. */
@Service
@Profile("!api-docs")
public class NotificationSimilarityService {
  private final PersonalizationService users;
  private final SearchIndexService stores;
  private final double minimum;

  public NotificationSimilarityService(
      PersonalizationService users,
      SearchIndexService stores,
      @Value("${search.matching.minimum-similarity:0.5}") double minimum) {
    if (!Double.isFinite(minimum) || minimum < -1 || minimum > 1)
      throw new IllegalArgumentException(
          "search.matching.minimum-similarity must be between -1 and 1");
    this.users = users;
    this.stores = stores;
    this.minimum = minimum;
  }

  public boolean matches(String userId, Slot slot) {
    if (!users.hasPreferences(userId)) return false;
    float[] user = users.vector(userId);
    float[] store = stores.matchingVector(slot.storeId());
    if (user == null) users.requestMatchingRefresh(userId);
    if (user == null || store == null) {
      // 벡터 생성은 별도 작업이다. 준비 전에 통과시키거나 알림을 성공 처리해 유실하지 않는다.
      throw new EmbeddingFailure("MATCH_VECTORS_PENDING", true);
    }
    double score = cosine(user, store);
    boolean eligible = score >= minimum;
    org.slf4j.LoggerFactory.getLogger(getClass())
        .debug(
            "Notification similarity user={} store={} score={} minimum={} eligible={}",
            userId,
            slot.storeId(),
            score,
            minimum,
            eligible);
    return eligible;
  }

  static double cosine(float[] user, float[] store) {
    EmbeddingVector.validate(user);
    EmbeddingVector.validate(store);
    double dot = 0, left = 0, right = 0;
    for (int i = 0; i < user.length; i++) {
      dot += (double) user[i] * store[i];
      left += (double) user[i] * user[i];
      right += (double) store[i] * store[i];
    }
    return Math.max(-1, Math.min(1, dot / Math.sqrt(left * right)));
  }
}
