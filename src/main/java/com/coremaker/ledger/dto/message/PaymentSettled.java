package com.coremaker.ledger.dto.message;

import java.math.BigDecimal;
import java.util.UUID;

import com.coremaker.ledger.model.enums.Currency;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PaymentSettled(
        @NotBlank @Size(max = 200) String paymentId,
        @NotNull UUID merchantAccountId,
        @NotNull @Digits(integer = 15, fraction = 4) @DecimalMax("999999999999999.9999") BigDecimal amount,
        @NotNull Currency currency) {
}
