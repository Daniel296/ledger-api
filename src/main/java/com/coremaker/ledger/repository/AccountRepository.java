package com.coremaker.ledger.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.coremaker.ledger.model.Account;

public interface AccountRepository extends JpaRepository<Account, UUID> {
}
