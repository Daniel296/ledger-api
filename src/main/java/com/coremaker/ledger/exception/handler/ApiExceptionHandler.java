package com.coremaker.ledger.exception.handler;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.coremaker.ledger.dto.response.ErrorResponse;
import com.coremaker.ledger.exception.ErrorCode;
import com.coremaker.ledger.exception.LedgerException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler
    public ResponseEntity<ErrorResponse> ledgerError(LedgerException exception) {

        return ResponseEntity.status(statusOf(exception.getCode()))
                .body(new ErrorResponse(exception.getCode(), exception.getMessage()));
    }

    private static HttpStatus statusOf(ErrorCode code) {

        return switch (code) {
            case ACCOUNT_NOT_FOUND, TRANSACTION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case TRANSACTION_ALREADY_REVERSED, REVERSAL_NOT_REVERSIBLE -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
    }
}
