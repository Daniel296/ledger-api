package com.coremaker.ledger.exception.handler;

import static com.coremaker.ledger.exception.ErrorCode.ACCOUNT_BUSY;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.coremaker.ledger.dto.response.ErrorResponse;
import com.coremaker.ledger.exception.ErrorCode;
import com.coremaker.ledger.exception.LedgerException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final String RETRY_AFTER_SECONDS = "1";

    @ExceptionHandler
    public ResponseEntity<ErrorResponse> ledgerError(LedgerException exception) {

        return ResponseEntity.status(statusOf(exception.getCode()))
                .body(new ErrorResponse(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> accountBusy() {

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(new ErrorResponse(ACCOUNT_BUSY,
                        "The accounts of this request are busy with other transactions; retry later"));
    }

    private static HttpStatus statusOf(ErrorCode code) {

        return switch (code) {
            case ACCOUNT_NOT_FOUND, TRANSACTION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case TRANSACTION_ALREADY_REVERSED, REVERSAL_NOT_REVERSIBLE -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
    }
}
