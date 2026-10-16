package com.bintime.search.config;

import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@Profile("!api-docs")
public class SearchTransactions {
  @Bean
  TransactionTemplate searchTransactionTemplate(PlatformTransactionManager manager) {
    return new TransactionTemplate(manager);
  }
}
