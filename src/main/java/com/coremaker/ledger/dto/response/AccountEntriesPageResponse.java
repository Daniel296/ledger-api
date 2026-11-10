package com.coremaker.ledger.dto.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountEntriesPageResponse(List<AccountEntryResponse> entries, String nextCursor) {
}
