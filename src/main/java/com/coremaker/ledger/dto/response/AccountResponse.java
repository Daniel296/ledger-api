package com.coremaker.ledger.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.model.enums.Currency;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountResponse(UUID id, Currency currency, String overdraftLimit, String balance, Instant createdAt) {

    public static AccountResponse from(Account account) {

        var currency = account.getCurrency();
        var overdraftLimit = account.isUnlimitedOverdraft() ? null : currency.format(account.getOverdraftLimit());
        var balance = currency.format(account.getBalance());
        return new AccountResponse(account.getId(), currency, overdraftLimit, balance, account.getCreatedAt());
    }
}
