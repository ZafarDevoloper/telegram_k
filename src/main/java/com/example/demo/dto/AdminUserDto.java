package com.example.demo.dto;

import com.example.demo.entity.AdminUser;

/**
 * AdminUserDto — API javoblarida AdminUser o'rniga ishlatiladi.
 *
 * Nima yashiriladi:
 *   - password (hatto hash bo'lsa ham)
 *   - Kelajakda qo'shiladigan sezgir maydonlar
 *
 * Foydalanish:
 *   AdminUserDto.from(adminUser) — entity dan DTO ga
 */
public record AdminUserDto(
        Long   id,
        String username,
        String fullName,
        String role,
        boolean active
) {
    public static AdminUserDto from(AdminUser user) {
        return new AdminUserDto(
                user.getId(),
                user.getUsername(),
                user.getFullName() != null ? user.getFullName() : "",
                user.getRole().name(),
                user.isActive()
        );
    }
}