package com.bintime.search.config;

import com.bintime.search.service.PersonalizationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 인증을 우회하는 공개 HTTP API 대신 운영자가 서버에서 작업을 등록한다. */
@Component
@Profile("!api-docs")
@ConditionalOnProperty(name = "search.vector.rebuild-user")
public class UserVectorRebuild implements ApplicationRunner {
  private final PersonalizationService vectors;
  private final String user;

  public UserVectorRebuild(
      PersonalizationService vectors, @Value("${search.vector.rebuild-user}") String user) {
    this.vectors = vectors;
    this.user = user;
  }

  public void run(ApplicationArguments args) {
    if (user.isBlank() || user.length() > 64)
      throw new IllegalArgumentException("Invalid rebuild user ID");
    vectors.requestRefresh(user);
    org.slf4j.LoggerFactory.getLogger(getClass()).info("User vector refresh queued");
  }
}
