package com.coremaker.ledger.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Currency {

    EUR(2),
    USD(2),
    JPY(0);

    private final int decimals;

    public boolean fits(BigDecimal amount) {

        return amount.stripTrailingZeros().scale() <= decimals;
    }

    public String format(BigDecimal amount) {

        return amount.setScale(decimals, RoundingMode.UNNECESSARY).toPlainString();
    }
}
