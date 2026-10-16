package com.bintime.user.contract;

import jakarta.servlet.http.HttpServletRequest;

/** user 구현이 세션, CUSTOMER 역할, 변경 요청의 CSRF를 검증한다. */
public interface SearchUserAccess {
  String customer(HttpServletRequest request, String csrfToken, boolean mutation);

  boolean notificationsEnabled(String userId);

  /** 자연어를 포함하는 자유로운 JSON 전체. 원본을 특정 선호 DTO로 변환하지 않는다. */
  String explicitPreferences(String userId);
}
