package com.coremaker.ledger.integration.support;

import org.springframework.context.ConfigurableApplicationContext;

import com.coremaker.ledger.service.TransactionService;

public record SecondInstance(ConfigurableApplicationContext context) implements AutoCloseable {

    public TransactionService transactionService() {

        return context.getBean(TransactionService.class);
    }

    @Override
    public void close() {

        context.close();
    }
}
