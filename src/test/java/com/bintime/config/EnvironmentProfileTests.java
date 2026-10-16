package com.bintime.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;

class EnvironmentProfileTests {
  @TempDir Path directory;

  @Test
  void localProfileLoadsConnectionValuesFromAnExtensionlessEnvFile() throws Exception {
    Path env = directory.resolve(".env");
    Files.writeString(
        env,
        """
        DB_URL=jdbc:oracle:thin:@//localhost:11521/FREEPDB1
        DB_USERNAME=LOCAL_PROBE
        DB_PASSWORD=local-probe-password
        REDIS_HOST=127.0.0.1
        REDIS_PORT=16379
        REDIS_PASSWORD=local-redis-password
        """);

    try (var context =
        application()
            .profiles("local")
            .run("--spring.config.import=file:" + env + "[.properties]")) {
      var environment = context.getEnvironment();
      assertThat(environment.getProperty("spring.datasource.url"))
          .isEqualTo("jdbc:oracle:thin:@//localhost:11521/FREEPDB1");
      assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("LOCAL_PROBE");
      assertThat(environment.getProperty("spring.datasource.password"))
          .isEqualTo("local-probe-password");
      assertThat(environment.getProperty("spring.data.redis.host")).isEqualTo("127.0.0.1");
      assertThat(environment.getProperty("spring.data.redis.port")).isEqualTo("16379");
      assertThat(environment.getProperty("spring.data.redis.password"))
          .isEqualTo("local-redis-password");
    }
  }

  @Test
  void prodUsesInjectedValuesWithoutImportingTheLocalEnvFile() {
    try (var context =
        application()
            .profiles("prod")
            .run(
                "--DB_URL=jdbc:oracle:thin:@//prod.example:1522/service",
                "--DB_USERNAME=PROD_PROBE",
                "--DB_PASSWORD=prod-probe-password",
                "--REDIS_HOST=redis",
                "--REDIS_PASSWORD=prod-redis-password")) {
      var environment = context.getEnvironment();
      assertThat(environment.getProperty("spring.config.import")).isNull();
      assertThat(environment.getProperty("spring.datasource.url"))
          .isEqualTo("jdbc:oracle:thin:@//prod.example:1522/service");
      assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("PROD_PROBE");
      assertThat(environment.getProperty("spring.datasource.password"))
          .isEqualTo("prod-probe-password");
      assertThat(environment.getProperty("spring.data.redis.host")).isEqualTo("redis");
      assertThat(environment.getProperty("spring.data.redis.port")).isEqualTo("6379");
      assertThat(environment.getProperty("spring.data.redis.password"))
          .isEqualTo("prod-redis-password");
    }
  }

  private SpringApplicationBuilder application() {
    // 설정 로딩만 검증하며 DataSource나 Redis 연결을 만들지 않는다.
    return new SpringApplicationBuilder(ProfileProbe.class)
        .web(WebApplicationType.NONE)
        .logStartupInfo(false)
        .registerShutdownHook(false)
        .properties("spring.main.banner-mode=off");
  }

  @Configuration(proxyBeanMethods = false)
  static class ProfileProbe {}
}
