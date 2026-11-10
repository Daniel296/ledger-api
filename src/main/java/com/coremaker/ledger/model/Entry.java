package com.coremaker.ledger.model;

import java.math.BigDecimal;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import com.coremaker.ledger.model.enums.Direction;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "entries")
@Immutable
@Getter
@Setter
public class Entry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", insertable = false, updatable = false)
    private Transaction transaction;

    private UUID accountId;

    @Enumerated(EnumType.STRING)
    private Direction direction;

    private BigDecimal amount;
    private BigDecimal balanceAfter;
}
