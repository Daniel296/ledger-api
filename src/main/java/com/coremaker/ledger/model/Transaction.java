package com.coremaker.ledger.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Transaction {

    private UUID id;
    private String description;
    private Instant recordedAt;
    private List<Entry> entries = new ArrayList<>();
}
