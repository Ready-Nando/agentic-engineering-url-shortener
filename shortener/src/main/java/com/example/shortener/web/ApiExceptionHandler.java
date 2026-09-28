package com.example.shortener.web;

import com.example.shortener.analytics.InvalidStatsWindowException;
import com.example.shortener.link.AliasUnavailableException;
import com.example.shortener.link.CodeGenerationException;
import com.example.shortener.link.IdempotencyKeyReuseException;
import com.example.shortener.link.InvalidAliasException;
import com.example.shortener.link.InvalidIdempotencyKeyException;
import com.example.shortener.link.InvalidTargetUrlException;
import com.example.shortener.link.LinkGoneException;
import com.example.shortener.link.LinkNotFoundException;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every error to an RFC 9457 problem document. Application problems get a stable type URI
 * under {@link #TYPE_BASE}; errors that carry no meaning beyond their HTTP status (unknown route,
 * unsupported method or media type) keep the RFC's {@code about:blank} type from Spring's defaults.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    public static final String TYPE_BASE = "https://example.com/problems/";

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidTargetUrlException.class)
    ProblemDetail invalidTargetUrl(InvalidTargetUrlException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-target-url", "Invalid target URL", e.getMessage());
    }

    @ExceptionHandler(InvalidAliasException.class)
    ProblemDetail invalidAlias(InvalidAliasException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-alias", "Invalid alias", e.getMessage());
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    ProblemDetail invalidIdempotencyKey(InvalidIdempotencyKeyException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-idempotency-key", "Invalid idempotency key", e.getMessage());
    }

    @ExceptionHandler(InvalidStatsWindowException.class)
    ProblemDetail invalidStatsWindow(InvalidStatsWindowException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-stats-window", "Invalid statistics window", e.getMessage());
    }

    @ExceptionHandler(LinkNotFoundException.class)
    ProblemDetail linkNotFound(LinkNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "link-not-found", "Link not found", e.getMessage());
    }

    @ExceptionHandler(LinkGoneException.class)
    ProblemDetail linkGone(LinkGoneException e) {
        return problem(HttpStatus.GONE, "link-gone", "Link disabled", e.getMessage());
    }

    @ExceptionHandler(AliasUnavailableException.class)
    ProblemDetail aliasUnavailable(AliasUnavailableException e) {
        return problem(HttpStatus.CONFLICT, "alias-unavailable", "Alias unavailable", e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    ProblemDetail idempotencyKeyReuse(IdempotencyKeyReuseException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reuse", "Idempotency key reused", e.getMessage());
    }

    @ExceptionHandler(CodeGenerationException.class)
    ProblemDetail codeGenerationFailed(CodeGenerationException e) {
        log.error(e.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "code-generation-failed", "Code generation failed",
                "Could not allocate a unique short code, please retry");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled exception while processing request", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal server error",
                "An unexpected error occurred");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                "The request contains invalid fields");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // The parser's own message can expose internal type names, so it is not passed on.
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request",
                "The request body is missing or is not valid JSON");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "invalid-parameter", "Invalid parameter",
                "Parameter '" + ex.getPropertyName() + "' has an invalid value");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + type));
        problem.setTitle(title);
        return problem;
    }

    public record FieldViolation(String field, String message) {
    }
}
