package com.coremaker.ledger.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.model.enums.Currency;

import jakarta.persistence.LockModeType;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id in :ids order by a.id")
    List<Account> findAllByIdForUpdate(Collection<UUID> ids);

    @Query("select a.id from Account a where a.unlimitedOverdraft = true and a.currency = :currency")
    Optional<UUID> findSettlementAccountId(Currency currency);
}
