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

import com.coremaker.ledger.dto.request.OpenAccountRequest;
import com.coremaker.ledger.dto.response.AccountResponse;
import com.coremaker.ledger.service.AccountService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @GetMapping("/{accountId}")
    public AccountResponse get(@PathVariable UUID accountId) {

        return accountService.get(accountId);
    }

    @PostMapping
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request) {

        var account = accountService.open(request.currency(), request.overdraftLimit());
        var location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(account.id())
                .toUri();
        return ResponseEntity.created(location).body(account);
    }
}
