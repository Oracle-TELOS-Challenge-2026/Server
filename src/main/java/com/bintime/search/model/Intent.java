package com.bintime.search.model;

import com.bintime.search.dto.WaitlistConditions;

public record Intent(
    String id,
    String userId,
    String source,
    WaitlistConditions conditions,
    String status,
    long version,
    long createdAt,
    long consentAt,
    String reservationId) {}
