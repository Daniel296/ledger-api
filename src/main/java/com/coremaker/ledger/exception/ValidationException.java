package com.coremaker.ledger.exception;

import lombok.Getter;

@Getter
public class ValidationException extends RuntimeException {

    private final ErrorCode code;

    public ValidationException(ErrorCode code, String message) {

        super(message);
        this.code = code;
    }
}
