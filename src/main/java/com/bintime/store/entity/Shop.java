package com.bintime.store.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "store_shops")
public class Shop {
  @Id
  @Column(name = "store_id", length = 64, nullable = false)
  public String id;

  @Column(name = "owner_user_id", length = 64, nullable = false)
  public String ownerId;

  @Column(name = "name", nullable = false)
  public String name;

  @Column(name = "category_code", nullable = false)
  public String category;

  @Column(name = "address", nullable = false)
  public String address;

  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.NUMERIC)
  @Column(name = "latitude", nullable = false)
  public Double latitude;

  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.NUMERIC)
  @Column(name = "longitude", nullable = false)
  public Double longitude;

  @Column(name = "description")
  public String description;

  @Column(name = "image_url")
  public String imageUrl;

  @Lob
  @Column(name = "opening_hours_json", nullable = false)
  public String openingHours;

  @Column(name = "timezone", nullable = false)
  public String timezone;

  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.NUMERIC)
  @Column(name = "rating_average", nullable = false)
  public Double ratingAverage;

  @Column(name = "rating_count", nullable = false)
  public Integer ratingCount;

  @Column(name = "version_no", nullable = false)
  public Long version;

  @org.hibernate.annotations.ColumnDefault("1")
  @Column(name = "search_version", nullable = false)
  public Long searchVersion = 1L;

  @Column(name = "created_at", nullable = false)
  public OffsetDateTime createdAt;
}
