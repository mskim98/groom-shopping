package groom.backend.interfaces.raffle.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class WinnerDto {
    private String userId;
    private Integer rank;
    private String userName;
    private String userEmail;
    private LocalDateTime createdAt;
}
