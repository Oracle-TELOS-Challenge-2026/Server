package com.bintime.search.config;

import com.bintime.search.service.SearchIndexService;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 운영자가 명시한 경우에만 원장 전체를 읽어 인덱스를 재구축한다. */
@Component
@Profile("!api-docs")
@ConditionalOnProperty(name = "search.index.rebuild", havingValue = "true")
@Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
public class SearchIndexRebuild implements ApplicationRunner {
  private final SearchIndexService indexes;

  public SearchIndexRebuild(SearchIndexService indexes) {
    this.indexes = indexes;
  }

  public void run(ApplicationArguments args) {
    LoggerFactory.getLogger(getClass()).info("Search index rebuilt: {}", indexes.rebuild());
  }
}
