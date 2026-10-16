package com.bintime.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.bintime.search.config.SearchScheduling;
import com.bintime.search.service.SearchWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class SearchSchedulingTests {
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  static class OtherDomainScheduling {}

  @Test
  void disablingSearchSurvivesAnotherDomainsSchedulingActivation() {
    new ApplicationContextRunner()
        .withUserConfiguration(SearchScheduling.class, OtherDomainScheduling.class)
        .withBean(SearchWorker.class, () -> mock(SearchWorker.class))
        .withPropertyValues("search.scheduling-enabled=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(SearchScheduling.class);
              assertThat(
                      context
                          .getBean(ScheduledAnnotationBeanPostProcessor.class)
                          .getScheduledTasks())
                  .isEmpty();
            });
  }
}
