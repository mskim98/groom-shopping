package groom.backend.controller;

import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {

    // /api/users GET 요청
    @GetMapping("/users")
    public List<UserResponse> getUsers() {
        System.out.println("API TEST 성공");
        return Arrays.asList(
                new UserResponse(1L, "Alice"),
                new UserResponse(2L, "Bob"),
                new UserResponse(3L, "Charlie")
        );
    }

    // 단순 DTO
    public static class UserResponse {
        private Long id;
        private String name;

        public UserResponse(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }
}
