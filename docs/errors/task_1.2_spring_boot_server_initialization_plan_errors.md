# Task 1.2 Spring Boot Server Initialization Review Fix Plan

## Review Findings

### P2 traceId contract mismatch

- Source: fresh code review agent.
- Issue: `contracts/web-api/openapi.yaml` declares `traceId` as `format: uuid`, but `TraceIdFilter` accepts and echoes any `X-Trace-Id` value.
- Risk: clients generated from OpenAPI may reject responses containing non-UUID trace IDs.

### P3 404 unified response may not be reached

- Source: fresh code review agent.
- Issue: `GlobalExceptionHandler` handles `NoHandlerFoundException`, but application configuration does not force Spring MVC to throw it for unmatched routes. Spring Boot 3.5 documentation also notes default static resource handling can prevent `NoHandlerFoundException`.
- Risk: unmatched routes may return Spring's default error payload instead of `ApiResponse`.

## Fix Plan

1. Update `TraceIdFilter` to only propagate valid UUID `X-Trace-Id` values; generate a new UUID when the request header is missing, blank, or invalid.
2. Update tests to use UUID-shaped trace IDs and add coverage for invalid trace ID fallback.
3. Configure MVC/static-resource 404 handling so unmatched routes can flow through global exception handling.
4. Add exception coverage for Spring static resource misses.
5. Re-run `mvn test`, restart the service, and manually verify `/api/health`, `/v3/api-docs`, Swagger UI, and an unmatched API route.

## Fix Execution Log

- Implemented UUID-only trace propagation in `TraceIdFilter`; invalid or blank incoming `X-Trace-Id` now falls back to a generated UUID.
- Added MVC/static-resource 404 configuration and `NoResourceFoundException` handling so unmatched routes can return `ApiResponse`.
- Updated tests to use UUID trace IDs and added coverage for invalid trace IDs and unified 404 responses.
- Verification: `mvn test` passed with 10 tests, 0 failures, 0 errors, 0 skipped.

## Additional Verification Finding

### P2 unauthenticated unknown API routes returned 401 before MVC 404 handling

- Source: final real HTTP verification against the packaged jar.
- Issue: `GET /api/not-found` without authentication was intercepted by Spring Security and returned 401 before Spring MVC could raise a not-found exception.
- Risk: unknown routes could return an authentication error instead of the unified `NOT_FOUND` response, making API behavior inconsistent with the global exception strategy.

## Additional Fix Plan

1. Add a Security request matcher that detects requests with no matching Spring MVC handler.
2. Permit only those unmatched requests through Security so global 404 handling can produce `ApiResponse`.
3. Keep 401 coverage on a real mapped but protected endpoint.
4. Re-run tests, rebuild the jar, restart the service, and verify `/api/not-found` returns unified 404 without authentication.

## Additional Fix Execution Log

- Added a `HandlerMappingIntrospector`-based matcher in `SecurityConfig` for unmatched MVC routes.
- Updated the unauthenticated-response test to call an existing protected test endpoint, preserving 401 coverage while allowing unknown routes to resolve as 404.

## Final Review Findings

### P2 contract/code generation closure is incomplete

- Source: final fresh code review agent.
- Issue: `contracts/web-api/openapi.yaml` defines response models, while Task 1.2 runtime code still provides the base response envelope in `common/api`.
- Decision: record as follow-up architecture work because this task initializes the Spring Boot service foundation and the project does not yet have a committed web-api generation pipeline for server models. Introducing full generation now would broaden the task beyond the requested foundation.

### P2 error responses omitted explicit `data`

- Source: final fresh code review agent.
- Issue: `ApiResponse` omitted null fields, so error responses did not always include `data`.
- Risk: response shape could differ between success and failure responses.

### P3 unmatched-route Security matcher was too broad

- Source: final fresh code review agent.
- Issue: the no-handler matcher applied outside `/api/**`, which could be risky if future non-MVC endpoints are added.
- Risk: future non-MVC endpoints could be misclassified as unmatched MVC routes.

### P3 Swagger UI test was shallow

- Source: final fresh code review agent.
- Issue: the test only accepted redirect/status for `/swagger-ui.html` and did not verify the actual index page content.

## Final Review Fix Plan

1. Make `ApiResponse` serialize `data: null` for failure responses.
2. Update the OpenAPI `ApiResponse` and `ErrorResponse` schemas so `data` is required and nullable.
3. Restrict the unmatched-route Security matcher to `/api/**`.
4. Change the 404 test to anonymous access and assert null `data` in failure responses.
5. Strengthen Swagger UI coverage by asserting `/swagger-ui/index.html` contains `Swagger UI`.

## Final Review Fix Execution Log

- Removed null-field suppression from `ApiResponse` so every response includes `data`.
- Updated the web API contract to require nullable `data` in response envelopes.
- Limited the Security unmatched-route pass-through to `/api/**`.
- Added test assertions for failure-response `data: null`, anonymous API 404, and Swagger UI index content.
