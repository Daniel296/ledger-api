package com.coremaker.ledger.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.coremaker.ledger.model.IdempotentRequest;

import jakarta.persistence.LockModeType;

public interface IdempotentRequestRepository extends JpaRepository<IdempotentRequest, String> {

    @Modifying
    @Query("insert into IdempotentRequest (idempotencyKey) values (:idempotencyKey) on conflict do nothing")
    void reserve(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from IdempotentRequest r where r.idempotencyKey = :idempotencyKey")
    IdempotentRequest findByIdForUpdate(String idempotencyKey);
}
