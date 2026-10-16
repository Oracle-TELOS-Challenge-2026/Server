package com.bintime.store.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "store_idempotency")
public class IdempotencyRecord {
  @Id
  @Column(name = "request_id", length = 64, nullable = false)
  public String id;

  @Column(name = "actor_id", length = 64, nullable = false)
  public String actorId;

  @Column(name = "operation", nullable = false)
  public String operation;

  @Column(name = "key_value", nullable = false)
  public String key;

  @Column(name = "fingerprint", nullable = false)
  public String fingerprint;

  @Lob
  @Column(name = "result_json", nullable = false)
  public String result;

  @Column(name = "http_status", nullable = false)
  public Integer status;

  @Column(name = "expires_at", nullable = false)
  public OffsetDateTime expiresAt;
}
