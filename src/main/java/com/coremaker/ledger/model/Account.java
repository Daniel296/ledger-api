package com.coremaker.ledger.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.coremaker.ledger.model.enums.Currency;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "accounts")
@Getter
@Setter
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    private Currency currency;

    private BigDecimal balance = BigDecimal.ZERO;
    private BigDecimal overdraftLimit = BigDecimal.ZERO;
    private boolean unlimitedOverdraft;
    private Instant createdAt;
}
