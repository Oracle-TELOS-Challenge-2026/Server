package com.bintime.user.contract;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/** HTTP 보안 필터가 인증·CSRF를 검증하고 user 구현이 현재 CUSTOMER 또는 OWNER 역할을 확인한다. */
public interface SearchUserAccess {
  String customer(HttpServletRequest request, String csrfToken, boolean mutation);

  boolean notificationsEnabled(String userId);

  /** 자연어를 포함하는 자유로운 JSON 전체. 원본을 특정 선호 DTO로 변환하지 않는다. */
  String explicitPreferences(String userId);

  /** CUSTOMER/OWNER 계정을 ID 순으로 조회한다. 기존 recommendation_opt_in 값과 위치는 검사하지 않는다. */
  List<String> personalizedCandidates(String after, int limit);

  /** 현재 계정 존재·역할만 확인한다. 실제 발송에는 활성 기기와 벡터 검증도 필요하다. */
  boolean personalizedCandidateExists(String userId);
}
