package com.pricetrack.exchange.common.exception;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.pricetrack.exchange.blockchain.BlockchainConfigurationException;
import com.pricetrack.exchange.blockchain.OperatorNotReadyException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DuplicateLoginIdException.class)
    public ResponseEntity<ApiErrorResponse> duplicateLoginId(
            DuplicateLoginIdException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "LOGIN_ID_ALREADY_EXISTS", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> invalidCredentials(
            InvalidCredentialsException exception, HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ApiErrorResponse> invalidToken(
            InvalidTokenException exception, HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", exception.getMessage(), request);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> userNotFound(
            UserNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ApiErrorResponse> insufficientBalance(
            InsufficientBalanceException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "INSUFFICIENT_BALANCE", exception.getMessage(), request);
    }

    @ExceptionHandler(BalanceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> balanceNotFound(
            BalanceNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "BALANCE_NOT_INITIALIZED", exception.getMessage(), request);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> orderNotFound(
            OrderNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(UnsupportedSymbolException.class)
    public ResponseEntity<ApiErrorResponse> unsupportedSymbol(
            UnsupportedSymbolException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "UNSUPPORTED_SYMBOL", exception.getMessage(), request);
    }

    @ExceptionHandler(OperatorNotReadyException.class)
    public ResponseEntity<ApiErrorResponse> operatorNotReady(
            OperatorNotReadyException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "OPERATOR_NOT_READY", exception.getMessage(), request);
    }

    @ExceptionHandler(BlockchainConfigurationException.class)
    public ResponseEntity<ApiErrorResponse> blockchainUnavailable(
            BlockchainConfigurationException exception, HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "BLOCKCHAIN_UNAVAILABLE", exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> validation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        ApiErrorResponse body = new ApiErrorResponse(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_FAILED",
                "요청값이 올바르지 않습니다.",
                request.getRequestURI(),
                fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(status.value(), code, message, request.getRequestURI()));
    }
}
