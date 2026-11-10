package com.coremaker.ledger.filter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.coremaker.ledger.logging.CorrelationId;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class RequestIdFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID = "X-Request-Id";
    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        var requestId = Optional.ofNullable(request.getHeader(REQUEST_ID))
                .filter(id -> VALID_REQUEST_ID.matcher(id).matches())
                .orElseGet(() -> UUID.randomUUID().toString());
        response.setHeader(REQUEST_ID, requestId);

        CorrelationId.set(requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            CorrelationId.clear();
        }
    }
}
