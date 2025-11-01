package groom.backend.interfaces.auth.persistence;

import groom.backend.domain.auth.entity.User;
import groom.backend.domain.auth.repository.UserRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JpaUserRepository implements UserRepository {

    private final SpringDataUserRepository springRepo;

    public JpaUserRepository(SpringDataUserRepository springRepo) {
        this.springRepo = springRepo;
    }

    @Override
    public Optional<User> findById(Long id) {
        return springRepo.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return springRepo.findByEmail(email.toLowerCase()).map(this::toDomain);
    }

    @Override
    public User save(User user) {
        UserJpaEntity saved = springRepo.save(toEntity(user));
        return toDomain(saved);
    }

    @Override
    public boolean existsByEmail(String email) {
        return springRepo.existsByEmail(email.toLowerCase());
    }

    private User toDomain(UserJpaEntity e) {
        return new User(e.getId(),
                e.getEmail(),
                e.getPassword(),
                e.getName(),
                e.getRole(),
                e.getGrade(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }

    private UserJpaEntity toEntity(User u) {
        return new UserJpaEntity(u.getId(), u.getEmail(), u.getPassword(), u.getName(), u.getRole(), u.getGrade(), u.getCreatedAt(), u.getUpdatedAt());
    }
}
