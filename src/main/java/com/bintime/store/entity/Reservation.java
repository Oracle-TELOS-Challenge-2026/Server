package com.bintime.store.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "store_reservations")
public class Reservation {
  @Id
  @Column(name = "reservation_id", length = 64, nullable = false)
  public String id;

  @Column(name = "user_id", length = 64, nullable = false)
  public String userId;

  @Column(name = "store_id", length = 64, nullable = false)
  public String storeId;

  @Column(name = "slot_id", length = 64, nullable = false)
  public String slotId;

  @Column(name = "waitlist_id", length = 64)
  public String waitlistId;

  @Column(name = "service_id", length = 64, nullable = false)
  public String serviceId;

  @Column(name = "service_name", nullable = false)
  public String serviceName;

  @Column(name = "service_description", length = 1000)
  public String serviceDescription;

  @Column(name = "duration_minutes", nullable = false)
  public Integer duration;

  @Column(name = "customer_name", nullable = false)
  public String customerName;

  @Column(name = "start_at", nullable = false)
  public OffsetDateTime start;

  @Column(name = "end_at", nullable = false)
  public OffsetDateTime end;

  @Column(name = "original_price", nullable = false)
  public Long originalPrice;

  @Column(name = "payable_price", nullable = false)
  public Long payable;

  @Column(name = "status_code", nullable = false)
  public String status;

  @Column(name = "cancel_reason_code")
  public String cancelReasonCode;

  @Column(name = "cancel_reason")
  public String cancelReason;

  @Column(name = "canceled_at")
  public OffsetDateTime canceledAt;

  @Column(name = "version_no", nullable = false)
  public Long version;

  @Column(name = "created_at", nullable = false)
  public OffsetDateTime createdAt;
}
