package com.coremaker.ledger.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.coremaker.ledger.model.Entry;

public interface EntryRepository extends JpaRepository<Entry, Long> {

    @Query("""
            select e from Entry e join fetch e.transaction
            where e.accountId = :accountId and e.id < :before
            order by e.id desc""")
    List<Entry> findNewestFirst(UUID accountId, long before, Limit limit);

    @Query("""
            select e.balanceAfter from Entry e
            where e.accountId = :accountId and e.transaction.recordedAt <= :asOf
            order by e.id desc
            limit 1""")
    Optional<BigDecimal> findBalanceAsOf(UUID accountId, Instant asOf);
}
