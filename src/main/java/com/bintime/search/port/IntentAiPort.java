package com.bintime.search.port;

import com.bintime.search.dto.*;
import java.time.OffsetDateTime;

public interface IntentAiPort {
  Extraction analyze(AnalyzeIntentRequest request, OffsetDateTime referenceTime);

  float[] embed(String text);

  record Extraction(
      @com.fasterxml.jackson.annotation.JsonProperty(required = true) boolean supported,
      @com.fasterxml.jackson.annotation.JsonProperty(required = true)
          PartialIntentConditions conditions,
      @com.fasterxml.jackson.annotation.JsonProperty(required = true) String serviceText) {
    public Extraction(boolean supported, PartialIntentConditions conditions) {
      this(supported, conditions, null);
    }
  }
}
