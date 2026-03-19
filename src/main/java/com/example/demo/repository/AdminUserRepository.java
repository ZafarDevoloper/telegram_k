package com.example.demo.repository;

import com.example.demo.entity.AdminUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AdminUserRepository extends JpaRepository<AdminUser, Long> {

    Optional<AdminUser> findByUsername(String username);

    boolean existsByUsername(String username);

    // Aktiv adminlar
    java.util.List<AdminUser> findByActiveTrue();

    // Role bo'yicha
    java.util.List<AdminUser> findByRoleAndActiveTrue(AdminUser.AdminRole role);
}