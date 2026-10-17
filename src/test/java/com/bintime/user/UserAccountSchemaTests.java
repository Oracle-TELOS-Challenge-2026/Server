package com.bintime.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.bintime.user.entity.UserAccount;
import java.sql.DriverManager;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;

class UserAccountSchemaTests {
  @Test
  void validatesExistingCharConsentColumnAndPersistsDefault() throws Exception {
    String url = "jdbc:h2:mem:user-account-schema;MODE=Oracle;DB_CLOSE_DELAY=-1";
    try (var connection = DriverManager.getConnection(url, "sa", "");
        var statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE user_accounts (
            user_id VARCHAR(64) PRIMARY KEY,
            display_name VARCHAR(255),
            role_code VARCHAR(255),
            recommendation_opt_in CHAR(1) DEFAULT 'N' NOT NULL,
            recommendation_consent_at TIMESTAMP(6) WITH TIME ZONE,
            preferences_json CLOB NOT NULL
          )
          """);
    }
    var registry =
        new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.url", url)
            .applySetting("hibernate.connection.username", "sa")
            .applySetting("hibernate.connection.password", "")
            .applySetting("hibernate.hbm2ddl.auto", "validate")
            .build();
    try (var factory =
        new MetadataSources(registry)
            .addAnnotatedClass(UserAccount.class)
            .buildMetadata()
            .buildSessionFactory()) {
      factory.inTransaction(
          session -> {
            var account = new UserAccount();
            account.id = "schema-test";
            account.displayName = "테스트";
            account.role = "CUSTOMER";
            account.preferences = "{}";
            session.persist(account);
          });
      factory.inTransaction(
          session ->
              assertThat(session.find(UserAccount.class, "schema-test").recommendationOptIn)
                  .isEqualTo("N"));
    } finally {
      StandardServiceRegistryBuilder.destroy(registry);
    }
  }
}
