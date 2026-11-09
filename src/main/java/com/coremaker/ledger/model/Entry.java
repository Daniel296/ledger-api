package com.coremaker.ledger.model;

import java.math.BigDecimal;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Entry {

    private UUID accountId;
    private Direction direction;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
}
