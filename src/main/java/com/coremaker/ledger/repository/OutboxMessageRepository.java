package com.coremaker.ledger.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.coremaker.ledger.model.OutboxMessage;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {

    @Query(value = "SELECT id FROM outbox_publisher FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<Integer> findUnlockedPublisherForUpdate();

    List<OutboxMessage> findFirst100ByPublishedAtIsNullOrderById();

    @Modifying
    @Query("update OutboxMessage m set m.publishedAt = :publishedAt where m.id in :ids")
    void markPublished(Collection<Long> ids, Instant publishedAt);
}
