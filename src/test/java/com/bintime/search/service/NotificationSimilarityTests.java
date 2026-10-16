package com.bintime.search.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bintime.store.contract.SearchStoreCatalog.Slot;
import org.junit.jupiter.api.Test;

class NotificationSimilarityTests {
  PersonalizationService users = mock(PersonalizationService.class);
  SearchIndexService stores = mock(SearchIndexService.class);
  Slot slot = mock(Slot.class);

  private float[] vector(float first, float second) {
    float[] value = new float[1536];
    value[0] = first;
    value[1] = second;
    return value;
  }

  private NotificationSimilarityService service(float[] user, float[] store, double threshold) {
    when(users.hasPreferences("user")).thenReturn(true);
    when(users.vector("user")).thenReturn(user);
    when(slot.storeId()).thenReturn("store");
    when(stores.matchingVector("store")).thenReturn(store);
    return new NotificationSimilarityService(users, stores, threshold);
  }

  @Test
  void usesRawNormalizedCosineAndInclusiveThreshold() {
    float[] user = vector(3, 0), store = vector(1, 2);
    double score = NotificationSimilarityService.cosine(user, store);
    assertThat(score).isCloseTo(1 / Math.sqrt(5), within(1e-9));
    assertThat(service(user, store, score).matches("user", slot)).isTrue();
    assertThat(service(user, store, Math.nextUp(score)).matches("user", slot)).isFalse();
    assertThat(service(vector(1, 0), vector(0, 1), 0.5).matches("user", slot)).isFalse();
    assertThat(service(vector(1, 0), vector(-1, 0), 0.5).matches("user", slot)).isFalse();
    assertThat(service(vector(1, 0), vector(1, 0), 0.5).matches("user", slot)).isTrue();
  }

  @Test
  void missingVectorsDeferNotificationAndRequestUserGeneration() {
    var matching = service(null, null, 0.5);
    assertThatThrownBy(() -> matching.matches("user", slot)).hasMessage("MATCH_VECTORS_PENDING");
    verify(users).requestMatchingRefresh("user");
  }

  @Test
  void noPreferenceDoesNotSendOrRequestEmbeddings() {
    assertThat(new NotificationSimilarityService(users, stores, 0.5).matches("user", slot))
        .isFalse();
    verify(users, never()).requestMatchingRefresh(anyString());
    verifyNoInteractions(stores);
  }

  @Test
  void rejectsInvalidVectorsAndInvalidConfiguration() {
    assertThatThrownBy(() -> service(vector(0, 0), vector(1, 0), 0.5).matches("user", slot))
        .hasMessage("OPENAI_INVALID_RESPONSE");
    float[] invalid = vector(1, 0);
    invalid[1] = Float.NaN;
    assertThatThrownBy(() -> NotificationSimilarityService.cosine(invalid, vector(1, 0)))
        .hasMessage("OPENAI_INVALID_RESPONSE");
    assertThatThrownBy(() -> NotificationSimilarityService.cosine(new float[2], vector(1, 0)))
        .hasMessage("OPENAI_INVALID_RESPONSE");
    assertThatThrownBy(() -> new NotificationSimilarityService(users, stores, Double.NaN))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new NotificationSimilarityService(users, stores, 1.1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
