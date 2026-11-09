package com.coremaker.ledger.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.coremaker.ledger.model.Transaction;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    @Override
    @EntityGraph(attributePaths = "entries")
    Optional<Transaction> findById(UUID id);
}
