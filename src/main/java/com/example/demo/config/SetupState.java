package com.example.demo.config;

import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SetupState — Tizim setup rejimida ekanligini saqlaydi.
 *
 * Thread-safe AtomicBoolean ishlatiladi.
 * DataInitializer startup da holatni belgilaydi.
 * AdminAuthController setup endpointida tekshiradi.
 */
@Component
public class SetupState {

    private final AtomicBoolean setupRequired = new AtomicBoolean(false);

    public boolean isSetupRequired() {
        return setupRequired.get();
    }

    public void setSetupRequired(boolean value) {
        setupRequired.set(value);
    }
}