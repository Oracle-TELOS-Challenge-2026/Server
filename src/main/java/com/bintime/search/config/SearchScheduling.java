package com.bintime.search.config;

import com.bintime.search.service.SearchWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@Profile("!api-docs")
@EnableScheduling
@ConditionalOnProperty(
    name = "search.scheduling-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SearchScheduling {
  private final SearchWorker worker;

  public SearchScheduling(SearchWorker worker) {
    this.worker = worker;
  }

  // 다른 도메인이 스케줄링을 활성화해도 search 비활성 설정은 유지한다.
  @Scheduled(fixedDelayString = "${search.worker-delay-ms:5000}")
  public void poll() {
    worker.poll();
  }

  @Scheduled(fixedDelayString = "${search.expiry-delay-ms:60000}")
  public void expire() {
    worker.expire();
  }
}
