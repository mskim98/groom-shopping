package groom.backend.interfaces.raffle.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
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
public class RaffleUpdateRequest {
    @Schema(description = "추첨 제품 ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID raffleProductId;
    @Schema(description = "당첨 제품 ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID winnerProductId;
    @Schema(description = "추첨 제목", example = "추첨 이벤트")
    @Size(max=100)
    private String title;
    @Schema(description = "추첨 설명", example = "추첨 설명입니다")
    @Size(max= 2000)
    private String description;
    @Schema(description = "당첨자 수", example = "10")
    @Min(1)
    private Integer winnersCount;
    @Schema(description = "사용자당 최대 참여 수", example = "5")
    @Min(1)
    private Integer maxEntriesPerUser;
    @Schema(type = "string", description = "참여 시작 일시", example = "2024-01-01T00:00:00")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime entryStartAt;
    @Schema(type = "string", description = "참여 종료 일시", example = "2024-01-31T23:59:59")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime entryEndAt;
    @Schema(type = "string", description = "추첨 일시", example = "2024-02-01T12:00:00")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime raffleDrawAt;
}
