package com.example.demo.exception;

/**
 * TooManyRequestsException — 429 xatosi uchun.
 * LoginAttemptService bu xatoni tashlaydi.
 * GlobalExceptionHandler ushlaydi va 429 qaytaradi.
 */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}