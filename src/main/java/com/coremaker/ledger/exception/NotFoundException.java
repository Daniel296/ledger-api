package com.coremaker.ledger.exception;

import lombok.Getter;

@Getter
public class NotFoundException extends RuntimeException {

    private final ErrorCode code;

    public NotFoundException(ErrorCode code, String message) {

        super(message);
        this.code = code;
    }
}
