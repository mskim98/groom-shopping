package groom.backend.domain.raffle.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class Participant {
    private Long userId;
    private String userName;
    private String userEmail;
    private LocalDateTime createdAt;

}
