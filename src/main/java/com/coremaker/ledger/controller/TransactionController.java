package com.coremaker.ledger.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.coremaker.ledger.dto.request.TransactionRequest;
import com.coremaker.ledger.dto.response.TransactionResponse;
import com.coremaker.ledger.service.TransactionService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @GetMapping("/{transactionId}")
    public TransactionResponse get(@PathVariable UUID transactionId) {

        return transactionService.get(transactionId);
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> post(
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 255) String idempotencyKey,
            @Valid @RequestBody TransactionRequest request) {

        return created(transactionService.post(idempotencyKey, request));
    }

    @PostMapping("/{transactionId}/reversal")
    public ResponseEntity<TransactionResponse> reverse(
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 255) String idempotencyKey,
            @PathVariable UUID transactionId) {

        return created(transactionService.reverse(idempotencyKey, transactionId));
    }

    private static ResponseEntity<TransactionResponse> created(TransactionResponse transaction) {

        var location = ServletUriComponentsBuilder
                .fromCurrentContextPath()
                .path("/v1/transactions/{id}")
                .buildAndExpand(transaction.id())
                .toUri();

        return ResponseEntity.created(location).body(transaction);
    }
}
