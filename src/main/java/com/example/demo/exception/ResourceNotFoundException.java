package com.example.demo.exception;

/**
 * ResourceNotFoundException — 404 xatosi uchun.
 * GlobalExceptionHandler bu xatoni ushlaydi va 404 qaytaradi.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String resource, Long id) {
        super(resource + " topilmadi: #" + id);
    }
}