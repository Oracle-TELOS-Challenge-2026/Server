package com.bintime.store.dto;

import com.bintime.common.dto.CursorPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "StoreServiceListResponse")
public record StoreServiceListResponse(
    @Schema(description = "items", requiredMode = Schema.RequiredMode.REQUIRED)
        List<StoreServiceResponse> items,
    @Schema(description = "page", requiredMode = Schema.RequiredMode.REQUIRED) CursorPage page) {}
