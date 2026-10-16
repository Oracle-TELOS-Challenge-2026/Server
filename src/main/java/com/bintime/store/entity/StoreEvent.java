package com.bintime.store.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "store_events")
public class StoreEvent {
  @Id
  @Column(name = "event_id", length = 64, nullable = false)
  public String id;

  @Column(name = "event_type", nullable = false)
  public String type;

  @Column(name = "slot_id", length = 64, nullable = false)
  public String slotId;

  @Column(name = "publication_version")
  public Long publicationVersion;

  @Column(name = "slot_version", nullable = false)
  public Long slotVersion;

  @Lob
  @Column(name = "payload_json", nullable = false)
  public String payload;

  @Column(name = "status_code", nullable = false)
  public String status;

  @Column(name = "created_at", nullable = false)
  public OffsetDateTime createdAt;
}
