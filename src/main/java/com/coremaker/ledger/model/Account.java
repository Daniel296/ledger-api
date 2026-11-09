package com.coremaker.ledger.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Account {

    private UUID id;
    private Currency currency;
    private BigDecimal overdraftLimit = BigDecimal.ZERO;
    private boolean unlimitedOverdraft;
    private BigDecimal balance = BigDecimal.ZERO;
    private Instant createdAt;
}
