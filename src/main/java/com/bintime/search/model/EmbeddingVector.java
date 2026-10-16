package com.bintime.search.model;

import com.bintime.search.service.EmbeddingFailure;

public final class EmbeddingVector {
  public static final int DIMENSIONS = 1536;

  private EmbeddingVector() {}

  public static float[] validate(float[] vector) {
    if (vector == null || vector.length != DIMENSIONS) throw EmbeddingFailure.invalidResponse();
    double norm = 0;
    for (float value : vector) {
      if (!Float.isFinite(value)) throw EmbeddingFailure.invalidResponse();
      norm += (double) value * value;
    }
    if (norm == 0) throw EmbeddingFailure.invalidResponse();
    return vector;
  }
}
