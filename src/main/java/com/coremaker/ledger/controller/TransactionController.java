package com.coremaker.ledger.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.coremaker.ledger.dto.request.RecordTransactionRequest;
import com.coremaker.ledger.dto.response.TransactionResponse;
import com.coremaker.ledger.service.TransactionService;

import jakarta.validation.Valid;
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
    public ResponseEntity<TransactionResponse> post(@Valid @RequestBody RecordTransactionRequest request) {

        var transaction = transactionService.post(request);
        var location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(transaction.id())
                .toUri();

        return ResponseEntity.created(location).body(transaction);
    }

}
