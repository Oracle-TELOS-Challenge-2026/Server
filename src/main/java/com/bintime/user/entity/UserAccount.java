package com.bintime.user.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "user_accounts")
public class UserAccount {
  @Id
  @Column(name = "user_id", length = 64)
  public String id;

  @Column(name = "display_name")
  public String displayName;

  @Column(name = "role_code")
  public String role;

  @org.hibernate.annotations.ColumnDefault("'N'")
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
  @Column(name = "recommendation_opt_in", nullable = false, length = 1)
  public String recommendationOptIn = "N";

  @Column(name = "recommendation_consent_at")
  public OffsetDateTime recommendationConsentAt;

  @org.hibernate.annotations.ColumnDefault("'{}'")
  @Lob
  @Column(name = "preferences_json", nullable = false)
  public String preferences;
}
