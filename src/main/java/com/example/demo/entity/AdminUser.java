package com.example.demo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

@Entity
@Table(name = "admin_users")
public class AdminUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    // [FIX 1] @JsonIgnore — API response da parol ko'rinmaydi
    @JsonIgnore
    @Column(nullable = false)
    private String password;

    private String fullName;

    @Enumerated(EnumType.STRING)
    private AdminRole role = AdminRole.OPERATOR;

    private boolean active = true;

    public enum AdminRole { SUPER_ADMIN, ADMIN, OPERATOR }

    public Long getId()                    { return id; }
    public void setId(Long id)             { this.id = id; }

    public String getUsername()            { return username; }
    public void setUsername(String u)      { this.username = u; }

    public String getPassword()            { return password; }
    public void setPassword(String p)      { this.password = p; }

    public String getFullName()            { return fullName; }
    public void setFullName(String fn)     { this.fullName = fn; }

    public AdminRole getRole()             { return role; }
    public void setRole(AdminRole role)    { this.role = role; }

    public boolean isActive()              { return active; }
    public void setActive(boolean active)  { this.active = active; }
}