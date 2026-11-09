package com.coremaker.ledger.exception;

import lombok.Getter;

@Getter
public class LedgerException extends RuntimeException {

    private final ErrorCode code;

    public LedgerException(ErrorCode code, String message) {

        super(message);
        this.code = code;
    }
}
