package com.coremaker.ledger.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.coremaker.ledger.model.Direction;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

public record EntryRequest(
        @NotNull UUID accountId,
        @NotNull Direction direction,
        @NotNull @Digits(integer = 15, fraction = 4) BigDecimal amount) {
}
