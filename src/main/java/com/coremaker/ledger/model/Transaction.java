package com.coremaker.ledger.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import com.coremaker.ledger.model.enums.Currency;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "transactions")
@Immutable
@Getter
@Setter
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    private Currency currency;

    private String description;
    private Instant recordedAt;
    private UUID reversalOf;

    @OneToMany(cascade = CascadeType.PERSIST)
    @JoinColumn(name = "transaction_id", nullable = false, updatable = false)
    @OrderBy("id")
    @Immutable
    private List<Entry> entries = new ArrayList<>();
}
