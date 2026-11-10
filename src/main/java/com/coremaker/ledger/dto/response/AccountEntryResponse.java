package com.coremaker.ledger.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.coremaker.ledger.model.Entry;
import com.coremaker.ledger.model.enums.Currency;
import com.coremaker.ledger.model.enums.Direction;

public record AccountEntryResponse(
        UUID transactionId, Direction direction, String amount, String balanceAfter, Instant recordedAt) {

    public static AccountEntryResponse from(Entry entry, Currency currency) {

        var transaction = entry.getTransaction();
        return new AccountEntryResponse(
                transaction.getId(),
                entry.getDirection(),
                currency.format(entry.getAmount()),
                currency.format(entry.getBalanceAfter()),
                transaction.getRecordedAt());
    }
}
