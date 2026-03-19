package com.example.demo.enums;

/**
 * ApplicationCategory — Murojaat kategoriyalari.
 * [FIX 5] Keraksiz jakarta.persistence importlari olib tashlandi.
 */
public enum ApplicationCategory {

    COMPLAINT("🚨 Shikoyat",      "complaint"),
    SUGGESTION("💡 Taklif",       "suggestion"),
    QUESTION("❓ Savol",          "question"),
    REQUEST("📋 So'rov",          "request"),
    TECHNICAL("🔧 Texnik muammo", "technical"),
    OTHER("📌 Boshqa",            "other");

    private final String label;
    private final String code;

    ApplicationCategory(String label, String code) {
        this.label = label;
        this.code  = code;
    }

    public String getLabel() { return label; }
    public String getCode()  { return code; }
}