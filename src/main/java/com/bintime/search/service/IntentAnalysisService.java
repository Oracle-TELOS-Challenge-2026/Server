package com.bintime.search.service;

import com.bintime.search.dto.*;
import com.bintime.search.port.IntentAiPort;
import com.bintime.store.contract.SearchStoreCatalog;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class IntentAnalysisService {
  private final IntentAiPort ai;
  private final Clock clock;
  private final ConditionRules rules;
  private final SearchStoreCatalog stores;

  public IntentAnalysisService(
      IntentAiPort ai, Clock clock, ConditionRules rules, SearchStoreCatalog stores) {
    this.ai = ai;
    this.clock = clock;
    this.rules = rules;
    this.stores = stores;
  }

  public AnalyzeIntentResponse analyze(AnalyzeIntentRequest request) {
    if (request == null
        || request.text() == null
        || request.text().isBlank()
        || request.text().length() > 4000) throw SearchFailure.bad("INVALID_TEXT");
    var context = request.context();
    if (context != null && context.location() != null)
      ConditionRules.coordinates(context.location().latitude(), context.location().longitude());
    OffsetDateTime reference = clock.instant().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
    var extraction = ai.analyze(request, reference);
    if (extraction == null || extraction.conditions() == null) throw SearchFailure.unavailable();
    var c = extraction.conditions();
    // AI 좌표/ID는 신뢰하지 않고 사용자가 제공한 컨텍스트만 승격한다.
    String store = context == null ? null : context.storeId();
    PartialIntentConditions.Location location =
        context == null || context.location() == null
            ? null
            : new PartialIntentConditions.Location(
                context.location().latitude(), context.location().longitude());
    c =
        new PartialIntentConditions(
            store,
            c.category(),
            null,
            c.windowStart(),
            c.windowEnd(),
            location,
            c.radiusMeters(),
            c.maxPrice(),
            c.keywords() == null ? List.of() : c.keywords());
    var missing = new ArrayList<String>();
    if (extraction.serviceText() != null && !extraction.serviceText().isBlank()
        || extraction.conditions().serviceId() != null) missing.add("serviceId");
    if (!ConditionRules.CATEGORIES.contains(Objects.toString(c.category(), "")))
      missing.add("category");
    if (c.windowStart() == null
        || c.windowEnd() == null
        || !c.windowStart().isBefore(c.windowEnd())
        || !c.windowEnd().toInstant().isAfter(clock.instant())) missing.add("timeWindow");
    if (store == null && location == null) missing.add("location");
    if (store == null
        && (c.radiusMeters() == null || c.radiusMeters() < 100 || c.radiusMeters() > 10000))
      missing.add("radiusMeters");
    if (c.maxPrice() != null && (c.maxPrice() < 0 || c.maxPrice() > 10000000))
      missing.add("maxPrice");
    if (missing.isEmpty())
      try {
        var confirmed =
            new WaitlistConditions(
                c.storeId(),
                c.category(),
                c.serviceId(),
                c.windowStart(),
                c.windowEnd(),
                location == null
                    ? null
                    : new WaitlistConditions.Location(location.latitude(), location.longitude()),
                c.radiusMeters(),
                c.maxPrice(),
                c.keywords());
        stores.validate(rules.normalize(confirmed, clock.instant()));
      } catch (SearchFailure e) {
        if (e.status.value() == 503) throw e;
        missing.add("storeOrService");
      }
    String resolution =
        !extraction.supported()
            ? "UNSUPPORTED"
            : missing.isEmpty() ? "RESOLVED" : "NEEDS_CLARIFICATION";
    return new AnalyzeIntentResponse(
        reference,
        "Asia/Seoul",
        c,
        resolution,
        missing,
        missing.isEmpty()
            ? null
            : "다음 조건을 선택하거나 확인해 주세요: "
                + missing.stream()
                    .map(
                        field ->
                            switch (field) {
                              case "category" -> "시술 분류";
                              case "timeWindow" -> "방문 가능 시간";
                              case "location" -> "위치";
                              case "radiusMeters" -> "이동 반경";
                              case "maxPrice" -> "예산";
                              case "serviceId" -> "정확한 시술";
                              default -> "매장과 시술";
                            })
                    .collect(java.util.stream.Collectors.joining(", ")));
  }
}
