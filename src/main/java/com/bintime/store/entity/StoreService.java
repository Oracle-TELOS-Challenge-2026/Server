package com.bintime.store.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "store_services")
public class StoreService {
  @Id
  @Column(name = "service_id", length = 64, nullable = false)
  public String id;

  @Column(name = "store_id", length = 64, nullable = false)
  public String storeId;

  @Column(name = "name", nullable = false)
  public String name;

  @Column(name = "duration_minutes", nullable = false)
  public Integer duration;

  @Column(name = "base_price", nullable = false)
  public Long price;

  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
  @Column(name = "active_yn", length = 1, nullable = false)
  public String active;

  @Column(name = "version_no", nullable = false)
  public Long version;
}
