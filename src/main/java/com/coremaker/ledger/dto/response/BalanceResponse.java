package com.coremaker.ledger.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.model.enums.Currency;

public record BalanceResponse(UUID accountId, Currency currency, String balance, Instant asOf) {

    public static BalanceResponse from(Account account, BigDecimal balance, Instant asOf) {

        var currency = account.getCurrency();
        return new BalanceResponse(account.getId(), currency, currency.format(balance), asOf);
    }
}
