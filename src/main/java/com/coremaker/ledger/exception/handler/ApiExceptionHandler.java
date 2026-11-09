package com.coremaker.ledger.exception.handler;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.coremaker.ledger.dto.response.ErrorResponse;
import com.coremaker.ledger.exception.NotFoundException;
import com.coremaker.ledger.exception.ValidationException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    public ErrorResponse brokenRule(ValidationException exception) {

        return new ErrorResponse(exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse notFound(NotFoundException exception) {

        return new ErrorResponse(exception.getCode(), exception.getMessage());
    }
}
