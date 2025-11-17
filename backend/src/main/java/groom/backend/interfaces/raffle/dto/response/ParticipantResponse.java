package groom.backend.interfaces.raffle.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
@Builder
@Schema(description = "추첨 응모자 응답 DTO")
public class ParticipantResponse {
    @Schema(description = "응모자 ID", example = "1")
    private Long userId;
    @Schema(description = "응모자 이름", example = "홍길동")
    private String username;
    @Schema(description = "응모자 Email", example = "user@test.com")
    private String email;
    @Schema(description = "응모 일시", example = "2024-01-15T10:00:00")
    private LocalDateTime createdAt;

}
