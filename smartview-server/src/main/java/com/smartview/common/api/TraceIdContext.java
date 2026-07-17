package com.smartview.common.api;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.MDC;

public final class TraceIdContext {

    public static final String TRACE_ID_KEY = "traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private TraceIdContext() {
    }

    public static String currentTraceId() {
        return Optional.ofNullable(MDC.get(TRACE_ID_KEY))
                .filter(traceId -> !traceId.isBlank())
                .orElseGet(() -> {
                    String traceId = UUID.randomUUID().toString();
                    MDC.put(TRACE_ID_KEY, traceId);
                    return traceId;
                });
    }

    public static void setTraceId(String traceId) {
        MDC.put(TRACE_ID_KEY, traceId);
    }

    public static void clear() {
        MDC.remove(TRACE_ID_KEY);
    }
}
