package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * ChatMessage — Suxbat tarixi bazada saqlanadi.
 *
 * senderType: "USER" | "ADMIN" | "SYSTEM"
 */
@Getter
@Setter
@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Qaysi murojaatga tegishli */
    private Long appId;

    /** Kim yozdi: USER, ADMIN, SYSTEM */
    private String senderType;

    /** Telegram chat ID yoki "system" */
    private String senderId;

    @Column(columnDefinition = "TEXT")
    private String message;

    private LocalDateTime sentAt = LocalDateTime.now();
}