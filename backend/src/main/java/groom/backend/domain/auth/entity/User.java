package groom.backend.domain.auth.entity;


import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class User {

    private final Long id;
    private final String email;
    private String password;
    private String name;
    private Role role;
    private Grade grade;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public User(Long id, String email, String password, String name, Role role, Grade grade, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.email = email;
        this.password = password;
        this.name = name;
        this.role = role;
        this.grade = grade;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

}
