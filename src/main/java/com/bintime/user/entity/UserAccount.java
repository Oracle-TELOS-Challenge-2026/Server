package com.bintime.user.entity;

import jakarta.persistence.*;

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

  @org.hibernate.annotations.ColumnDefault("'{}'")
  @Lob
  @Column(name = "preferences_json", nullable = false)
  public String preferences;
}
