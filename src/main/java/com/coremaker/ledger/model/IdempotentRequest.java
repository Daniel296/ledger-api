package com.coremaker.ledger.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "idempotent_requests")
@Getter
@Setter
public class IdempotentRequest {

    @Id
    private String idempotencyKey;

    private String requestHash;

    @Column(columnDefinition = "mediumtext")
    private String response;
}
