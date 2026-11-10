package com.coremaker.ledger.logging;

import org.slf4j.MDC;

public final class CorrelationId {

    private static final String MDC_KEY = "correlationId";

    private CorrelationId() {
    }

    public static String get() {

        return MDC.get(MDC_KEY);
    }

    public static void set(String correlationId) {

        MDC.put(MDC_KEY, correlationId);
    }

    public static void clear() {

        MDC.remove(MDC_KEY);
    }
}
