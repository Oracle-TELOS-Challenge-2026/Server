package com.bintime.search.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@Profile("!api-docs")
@EnableScheduling
@ConditionalOnProperty(
    name = "search.scheduling-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SearchScheduling {}
