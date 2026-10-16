package com.bintime.store.service;

import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;

@Configuration
public class StoreRuntimeConfig {
  @Bean
  @Primary
  public Clock storeClock() {
    return Clock.systemUTC();
  }

  @Configuration
  @Profile("!api-docs")
  @EnableScheduling
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      name = "bintime.store.expiration-enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class ExpirationConfig {
    @Bean
    StoreExpirationTask storeExpirationTask(StoreApplicationService service) {
      return new StoreExpirationTask(service);
    }
  }

  static class StoreExpirationTask {
    private final StoreApplicationService service;

    StoreExpirationTask(StoreApplicationService service) {
      this.service = service;
    }

    @Scheduled(fixedDelayString = "${bintime.store.expiration-delay-ms:60000}")
    public void expire() {
      for (String storeId : service.storeIds()) service.expireStore(storeId);
    }
  }
}
