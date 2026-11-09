package com.coremaker.ledger.dto.response;

import java.util.UUID;

import com.coremaker.ledger.model.Entry;
import com.coremaker.ledger.model.enums.Currency;
import com.coremaker.ledger.model.enums.Direction;

public record EntryResponse(UUID accountId, Direction direction, String amount) {

    static EntryResponse from(Entry entry, Currency currency) {

        return new EntryResponse(entry.getAccountId(), entry.getDirection(), currency.format(entry.getAmount()));
    }
}
