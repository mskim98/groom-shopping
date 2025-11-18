package groom.backend.interfaces.raffle.dto.response;

import groom.backend.domain.product.model.Product;
import groom.backend.domain.raffle.entity.Raffle;
import groom.backend.domain.raffle.enums.RaffleStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
@Builder
@Schema(description = "추첨 상세 응답 DTO")
public class RaffleDetailResponse {
    @Schema(description = "추첨 ID", example = "1")
    private Long raffleId;
    
    @Schema(description = "추첨 제품", example = "{product details here}")
    private Product raffleProduct;
    
    @Schema(description = "당첨 제품", example = "")
    private Product winnerProduct;
    
    @Schema(description = "추첨 제목", example = "추첨 이벤트")
    private String title;
    
    @Schema(description = "추첨 설명", example = "추첨 설명입니다")
    private String description;
    
    @Schema(description = "당첨자 수", example = "10")
    private int winnersCount;
    
    @Schema(description = "사용자당 최대 참여 수", example = "5")
    private int maxEntriesPerUser;
    
    @Schema(description = "참여 시작 일시", example = "2024-01-01T00:00:00")
    private LocalDateTime entryStartAt;
    
    @Schema(description = "참여 종료 일시", example = "2024-01-31T23:59:59")
    private LocalDateTime entryEndAt;
    
    @Schema(description = "추첨 일시", example = "2024-02-01T12:00:00")
    private LocalDateTime raffleDrawAt;
    
    @Schema(description = "추첨 상태", example = "OPEN")
    private RaffleStatus status;
    
    @Schema(description = "생성 일시", example = "2024-01-01T12:00:00")
    private LocalDateTime createdAt;
    
    @Schema(description = "수정 일시", example = "2024-01-01T12:00:00")
    private LocalDateTime updatedAt;

    public static RaffleDetailResponse from(Raffle raffle, Product raffleProduct, Product winnerProduct) {
        return RaffleDetailResponse.builder()
                .raffleId(raffle.getRaffleId())
                .raffleProduct(raffleProduct)
                .winnerProduct(winnerProduct)
                .title(raffle.getTitle())
                .description(raffle.getDescription())
                .winnersCount(raffle.getWinnersCount())
                .maxEntriesPerUser(raffle.getMaxEntriesPerUser())
                .entryStartAt(raffle.getEntryStartAt())
                .entryEndAt(raffle.getEntryEndAt())
                .raffleDrawAt(raffle.getRaffleDrawAt())
                .status(raffle.getStatus())
                .createdAt(raffle.getCreatedAt())
                .updatedAt(raffle.getUpdatedAt())
                .build();
    }
}
