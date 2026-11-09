package com.coremaker.ledger.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.coremaker.ledger.model.Transaction;

public record TransactionResponse(UUID id, String description, List<EntryResponse> entries, Instant recordedAt) {

    public static TransactionResponse from(Transaction transaction) {

        var entries = transaction.getEntries().stream()
                .map(entry -> EntryResponse.from(entry, transaction.getCurrency()))
                .toList();
        return new TransactionResponse(transaction.getId(), transaction.getDescription(), entries, transaction.getRecordedAt());
    }
}
