package com.coremaker.ledger.validation;

import static com.coremaker.ledger.exception.ErrorCode.REVERSAL_NOT_REVERSIBLE;
import static com.coremaker.ledger.exception.ErrorCode.TRANSACTION_ALREADY_REVERSED;

import org.springframework.stereotype.Component;

import com.coremaker.ledger.exception.LedgerException;
import com.coremaker.ledger.model.Transaction;

@Component
public class ReversalValidator {

    public void validate(Transaction original, boolean alreadyReversed) {

        if (original.getReversalOf() != null) {
            throw new LedgerException(REVERSAL_NOT_REVERSIBLE,
                    "Transaction %s is itself a reversal and cannot be reversed".formatted(original.getId()));
        }

        if (alreadyReversed) {
            throw new LedgerException(TRANSACTION_ALREADY_REVERSED,
                    "Transaction %s was already reversed".formatted(original.getId()));
        }
    }
}
