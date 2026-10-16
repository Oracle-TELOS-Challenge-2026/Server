package com.bintime.user.contract;

import jakarta.servlet.http.HttpServletRequest;

/** user 구현이 세션, CUSTOMER 역할, 변경 요청의 CSRF를 검증한다. */
public interface SearchUserAccess {
  String customer(HttpServletRequest request, String csrfToken, boolean mutation);

  boolean notificationsEnabled(String userId);

  String explicitPreferences(String userId);
}
