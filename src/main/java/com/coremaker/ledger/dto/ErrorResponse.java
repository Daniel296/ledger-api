package com.coremaker.ledger.dto;

import com.coremaker.ledger.exception.ErrorCode;

public record ErrorResponse(ErrorCode errorCode, String message) {
}
