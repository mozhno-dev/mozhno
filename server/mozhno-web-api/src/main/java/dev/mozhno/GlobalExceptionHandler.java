package dev.mozhno;

import dev.mozhno.exception.BadRequestException;
import dev.mozhno.exception.ConflictException;
import dev.mozhno.exception.ForbiddenException;
import dev.mozhno.exception.MozhnoException;
import dev.mozhno.exception.NotFoundException;
import dev.mozhno.exception.QuotaExceededException;
import dev.mozhno.mail.MailDeliveryException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Global exception handler that translates application exceptions into
 * structured JSON error responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException ex) {
        return buildError(HttpStatus.NOT_FOUND, ex);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(BadRequestException ex) {
        return buildError(HttpStatus.BAD_REQUEST, ex);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ConflictException ex) {
        return buildError(HttpStatus.CONFLICT, ex);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(ForbiddenException ex) {
        return buildError(HttpStatus.FORBIDDEN, ex);
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> handleQuotaExceeded(QuotaExceededException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.getMessage());
        body.put("code", ex.getErrorCode());
        body.put("current", ex.getCurrent());
        body.put("limit", ex.getLimit());
        body.put("planName", ex.getPlanName());
        putTraceId(body);
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Access denied");
        body.put("code", "FORBIDDEN");
        putTraceId(body);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .map(e -> Map.of("field", e.getField(), "message", e.getDefaultMessage() != null ? e.getDefaultMessage() : "invalid"))
            .toList();

        String detail = fieldErrors.stream()
            .map(f -> f.get("message") + " (" + f.get("field") + ")")
            .reduce((a, b) -> a + "; " + b)
            .orElse("");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Validation failed: " + detail);
        body.put("code", "VALIDATION_ERROR");
        body.put("details", fieldErrors);
        putTraceId(body);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMalformedJson(HttpMessageNotReadableException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Malformed request body");
        body.put("code", "BAD_REQUEST");
        putTraceId(body);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Method " + ex.getMethod() + " not supported for this endpoint");
        body.put("code", "METHOD_NOT_ALLOWED");
        putTraceId(body);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        long maxSizeMb = ex.getMaxUploadSize() / (1024 * 1024);
        String message = maxSizeMb > 0
            ? "File size exceeds the maximum allowed size of " + maxSizeMb + " MB"
            : "File size exceeds the maximum allowed size";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("code", "UPLOAD_SIZE_EXCEEDED");
        putTraceId(body);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    @ExceptionHandler(MailDeliveryException.class)
    public ResponseEntity<Map<String, Object>> handleMailDelivery(MailDeliveryException ex) {
        log.error("Mail delivery failed", ex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Could not send the email");
        body.put("code", "MAIL_DELIVERY_FAILED");
        putTraceId(body);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, Object>> handleMultipart(MultipartException ex) {
        if (ex.getCause() instanceof MaxUploadSizeExceededException cause) {
            return handleMaxUploadSize(cause);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Failed to process uploaded file");
        body.put("code", "UPLOAD_SIZE_EXCEEDED");
        putTraceId(body);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException ex) {
        log.error("Unhandled exception", ex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Internal server error");
        body.put("code", "INTERNAL_ERROR");
        putTraceId(body);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private void putTraceId(Map<String, Object> body) {
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            body.put("traceId", traceId);
        }
    }

    private ResponseEntity<Map<String, Object>> buildError(HttpStatus status, MozhnoException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.getMessage());
        body.put("code", ex.getErrorCode());
        putTraceId(body);
        return ResponseEntity.status(status).body(body);
    }
}
