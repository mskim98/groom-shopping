package groom.backend.interfaces.raffle.dto.request;

import groom.backend.domain.raffle.enums.RaffleStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "추첨 생성/수정 요청 DTO")
public class RaffleRequest {
    @Schema(description = "추첨 제품 ID", example = "550e8400-e29b-41d4-a716-446655440000", required = true)
    private UUID raffleProductId;
    
    @Schema(description = "당첨 제품 ID", example = "550e8400-e29b-41d4-a716-446655440000", required = true)
    private UUID winnerProductId;
    
    @Schema(description = "추첨 제목", example = "추첨 이벤트", required = true)
    @NotBlank
    private String title;
    
    @Schema(description = "추첨 설명", example = "추첨 설명입니다")
    private String description;
    
    @Schema(description = "추첨 상태", example = "OPEN")
    private RaffleStatus status;
    
    @Schema(description = "당첨자 수", example = "10", required = true)
    private int winnersCount;
    
    @Schema(description = "사용자당 최대 참여 수", example = "5", required = true)
    private int maxEntriesPerUser;
    
    @Schema(description = "참여 시작 일시", example = "2024-01-01T00:00:00", required = true)
    private LocalDateTime entryStartAt;
    
    @Schema(description = "참여 종료 일시", example = "2024-01-31T23:59:59", required = true)
    private LocalDateTime entryEndAt;
    
    @Schema(description = "추첨 일시", example = "2024-02-01T12:00:00", required = true)
    private LocalDateTime raffleDrawAt;
}
