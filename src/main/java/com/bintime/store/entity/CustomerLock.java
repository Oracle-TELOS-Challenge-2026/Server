package com.bintime.store.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "store_customer_locks")
public class CustomerLock {
  @Id
  @Column(name = "user_id", length = 64, nullable = false)
  public String id;
}
