package com.coremaker.ledger.model.enums;

import java.math.BigDecimal;

public enum Direction {

    DEBIT,
    CREDIT;

    public BigDecimal signed(BigDecimal amount) {

        return this == CREDIT ? amount : amount.negate();
    }

    public Direction opposite() {

        return this == CREDIT ? DEBIT : CREDIT;
    }
}
