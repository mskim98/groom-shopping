package groom.backend.interfaces.raffle.dto.response;

import groom.backend.domain.raffle.entity.Raffle;
import groom.backend.domain.raffle.enums.RaffleStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
@Builder
public class RaffleResponse {
    private Long raffleId;
    private String raffleProductId;
    private String winnerProductId;
    private String title;
    private String description;
    private int winnersCount;
    private int maxEntriesPerUser;
    private LocalDateTime entryStartAt;
    private LocalDateTime entryEndAt;
    private LocalDateTime raffleDrawAt;
    private RaffleStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static RaffleResponse from(Raffle raffle) {
        return RaffleResponse.builder()
                .raffleId(raffle.getRaffleId())
                .raffleProductId(raffle.getRaffleProductId())
                .winnerProductId(raffle.getWinnerProductId())
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
