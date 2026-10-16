package com.bintime.search.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "search_waitlists")
public class Waitlist {
  @Id
  @Column(name = "waitlist_id", length = 64)
  public String id;

  @Column(name = "user_id", length = 64)
  public String userId;

  @Column(name = "store_id", length = 64)
  public String storeId;

  @Column(name = "service_id", length = 64)
  public String serviceId;

  @Column(name = "category_code")
  public String category;

  @Column(name = "window_start")
  public OffsetDateTime start;

  @Column(name = "window_end")
  public OffsetDateTime end;

  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.NUMERIC)
  @Column(name = "latitude")
  public Double latitude;

  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.NUMERIC)
  @Column(name = "longitude")
  public Double longitude;

  @Column(name = "radius_meters")
  public Integer radius;

  @Column(name = "max_price")
  public Long maxPrice;

  @Column(name = "status_code")
  public String status;

  @Column(name = "version_no")
  public Long version;
}
