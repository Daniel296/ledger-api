package com.coremaker.ledger.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.coremaker.ledger.model.Transaction;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionResponse(
        UUID id, String description, List<EntryResponse> entries, Instant recordedAt, UUID reversalOf) {

    public static TransactionResponse from(Transaction transaction) {

        var entries = transaction.getEntries().stream()
                .map(entry -> EntryResponse.from(entry, transaction.getCurrency()))
                .toList();

        return new TransactionResponse(
                transaction.getId(),
                transaction.getDescription(),
                entries,
                transaction.getRecordedAt(),
                transaction.getReversalOf());
    }
}
