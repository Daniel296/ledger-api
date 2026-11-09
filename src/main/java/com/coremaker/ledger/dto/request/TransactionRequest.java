package com.coremaker.ledger.dto.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TransactionRequest(
        @NotBlank @Size(max = 255) String description,
        @NotNull @Size(max = 100) List<@NotNull @Valid EntryRequest> entries) {
}
