package com.bintime.search.config;

import com.bintime.notification.contract.SearchNotificationGateway;
import com.bintime.search.service.SearchFailure;
import com.bintime.store.contract.SearchStoreCatalog;
import com.bintime.user.contract.SearchUserAccess;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;

@Configuration
public class SearchConfiguration {
  @Bean
  @ConditionalOnMissingBean
  Clock searchClock() {
    return Clock.system(ZoneId.of("Asia/Seoul"));
  }

  @Bean
  @Profile("api-docs")
  @ConditionalOnMissingBean
  SearchUserAccess searchUserAccess(
      org.springframework.beans.factory.ObjectProvider<
              com.bintime.user.service.UserPreferenceService>
          preferences) {
    return new SearchUserAccess() {
      public String customer(
          jakarta.servlet.http.HttpServletRequest r, String csrf, boolean mutation) {
        if (r.getUserPrincipal() == null)
          throw new SearchFailure(
              org.springframework.http.HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED");
        throw SearchFailure.unavailable();
      }

      public boolean notificationsEnabled(String id) {
        throw SearchFailure.unavailable();
      }

      public String explicitPreferences(String id) {
        var service = preferences.getIfAvailable();
        if (service == null) throw SearchFailure.unavailable();
        return service.explicitPreferences(id);
      }
    };
  }

  // 일반 실행은 store의 실제 구현을 필수로 사용한다. DB 없는 Swagger에서만 대체한다.
  @Bean
  @Profile("api-docs")
  @ConditionalOnMissingBean
  SearchStoreCatalog searchStoreCatalog() {
    return new SearchStoreCatalog() {
      public void validate(com.bintime.search.dto.WaitlistConditions c) {
        throw SearchFailure.unavailable();
      }

      public Page available(com.bintime.search.dto.WaitlistConditions c, String cursor, int limit) {
        throw SearchFailure.unavailable();
      }

      public List<Slot> revalidate(Collection<String> ids) {
        throw SearchFailure.unavailable();
      }

      public References describe(String storeId, String serviceId) {
        throw SearchFailure.unavailable();
      }

      public StorePage export(String cursor, int limit) {
        throw SearchFailure.unavailable();
      }
    };
  }

  @Bean
  @Profile("api-docs")
  @ConditionalOnMissingBean
  SearchNotificationGateway searchNotifications() {
    return r -> {
      throw SearchFailure.unavailable();
    };
  }
}
