package com.coremaker.ledger.dto;

import java.util.UUID;

import com.coremaker.ledger.model.Currency;
import com.coremaker.ledger.model.Direction;
import com.coremaker.ledger.model.Entry;

public record EntryResponse(UUID accountId, Direction direction, String amount) {

    static EntryResponse from(Entry entry, Currency currency) {

        return new EntryResponse(entry.getAccountId(), entry.getDirection(), currency.format(entry.getAmount()));
    }
}
