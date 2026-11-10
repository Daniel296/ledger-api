package com.coremaker.ledger.unit.validation;

import static com.coremaker.ledger.exception.ErrorCode.REVERSAL_NOT_REVERSIBLE;
import static com.coremaker.ledger.exception.ErrorCode.TRANSACTION_ALREADY_REVERSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.coremaker.ledger.exception.ErrorCode;
import com.coremaker.ledger.exception.LedgerException;
import com.coremaker.ledger.model.Transaction;
import com.coremaker.ledger.validation.ReversalValidator;

class ReversalValidatorShould {

    private final ReversalValidator validator = new ReversalValidator();

    @Test
    void acceptATransactionNotReversedYet() {

        assertThatCode(() -> validator.validate(transaction(null), false)).doesNotThrowAnyException();
    }

    @Test
    void refuseToReverseATransactionTwice() {

        assertRefused(TRANSACTION_ALREADY_REVERSED, transaction(null), true);
    }

    @Test
    void refuseToReverseAReversal() {

        assertRefused(REVERSAL_NOT_REVERSIBLE, transaction(UUID.randomUUID()), false);
    }

    private void assertRefused(ErrorCode code, Transaction original, boolean alreadyReversed) {

        assertThatThrownBy(() -> validator.validate(original, alreadyReversed))
                .isInstanceOfSatisfying(LedgerException.class, refusal -> assertThat(refusal.getCode()).isEqualTo(code));
    }

    private static Transaction transaction(UUID reversalOf) {

        var transaction = new Transaction();
        transaction.setId(UUID.randomUUID());
        transaction.setReversalOf(reversalOf);
        return transaction;
    }
}
