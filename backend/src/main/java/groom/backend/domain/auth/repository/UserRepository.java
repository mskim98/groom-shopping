package groom.backend.domain.auth.repository;

import groom.backend.domain.auth.entity.User;

import java.util.Optional;

public interface UserRepository {
    Optional<User> findById(Long id);
    Optional<User> findByEmail(String email);
    User save(User user);
    boolean existsByEmail(String email);
}
