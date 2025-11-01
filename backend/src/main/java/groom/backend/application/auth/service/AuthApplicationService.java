package groom.backend.application.auth.service;

import groom.backend.domain.auth.entity.User;
import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import groom.backend.domain.auth.repository.UserRepository;
import groom.backend.interfaces.auth.dto.request.SignUpRequest;
import groom.backend.interfaces.auth.dto.response.SignUpResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthApplicationService {

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public SignUpResponse register(SignUpRequest req) {
        String email = req.getEmail().toLowerCase().trim();

        if (userRepo.existsByEmail(email)) {
            throw new IllegalArgumentException("Email already registered");
        }

        // TODO 객체 생성 팩토리 메서드로 변경 고려
        User user = new User(
                null,
                email,
                passwordEncoder.encode(req.getPassword()),
                req.getName(),
                Role.ROLE_USER,
                Grade.BRONZE,
                null,
                null
        );

        User saved = userRepo.save(user);

        return new SignUpResponse(saved.getId(), saved.getEmail(), saved.getName(),
                 saved.getGrade(), saved.getCreatedAt());

    }
}
