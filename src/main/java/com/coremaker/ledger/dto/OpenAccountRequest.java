package com.coremaker.ledger.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

public record OpenAccountRequest(@NotNull String currency, @Digits(integer = 15, fraction = 4) BigDecimal overdraftLimit) {
}
