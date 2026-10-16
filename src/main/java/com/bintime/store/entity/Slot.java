package com.bintime.store.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "store_slots")
public class Slot {
  @Id
  @Column(name = "slot_id", length = 64, nullable = false)
  public String id;

  @Column(name = "store_id", length = 64, nullable = false)
  public String storeId;

  @Column(name = "service_id", length = 64, nullable = false)
  public String serviceId;

  @Column(name = "start_at", nullable = false)
  public OffsetDateTime start;

  @Column(name = "end_at", nullable = false)
  public OffsetDateTime end;

  @Column(name = "available_from", nullable = false)
  public OffsetDateTime availableFrom;

  @Column(name = "available_until", nullable = false)
  public OffsetDateTime availableUntil;

  @Column(name = "original_price", nullable = false)
  public Long originalPrice;

  @Column(name = "payable_price", nullable = false)
  public Long payable;

  @Column(name = "status_code", nullable = false)
  public String status;

  @Column(name = "source_type", nullable = false)
  public String source;

  @Column(name = "source_reservation_id", length = 64)
  public String sourceReservationId;

  @Column(name = "original_service_id", length = 64)
  public String originalServiceId;

  @Column(name = "vacancy_original_price")
  public Long vacancyOriginalPrice;

  @Column(name = "publication_version")
  public Long publicationVersion;

  @Column(name = "published_at")
  public OffsetDateTime publishedAt;

  @Column(name = "version_no", nullable = false)
  public Long version;

  @Column(name = "created_at", nullable = false)
  public OffsetDateTime createdAt;

  @Column(name = "discount_type")
  public String discountType;

  @Column(name = "discount_value")
  public Long discountValue;
}
