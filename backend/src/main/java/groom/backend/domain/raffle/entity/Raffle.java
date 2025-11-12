package groom.backend.domain.raffle.entity;

import groom.backend.domain.raffle.enums.RaffleStatus;
import groom.backend.interfaces.raffle.dto.request.RaffleUpdateRequest;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@AllArgsConstructor
public class Raffle {

    private Long raffleId;
    private UUID raffleProductId;
    private UUID winnerProductId;
    private String title;
    private String description;
    private Integer winnersCount;
    private Integer maxEntriesPerUser;
    private LocalDateTime entryStartAt;
    private LocalDateTime entryEndAt;
    private LocalDateTime raffleDrawAt;
    private RaffleStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public void updateRaffle(RaffleUpdateRequest request) {
        if(request.getTitle() != null) {
            this.title = request.getTitle();
        }
        if(request.getDescription() != null) {
            this.description = request.getDescription();
        }
        if(request.getRaffleProductId() != null) {
            this.raffleProductId = request.getRaffleProductId();
        }
        if(request.getWinnerProductId() != null) {
            this.winnerProductId = request.getWinnerProductId();
        }
        if(request.getWinnersCount() != null) {
            this.winnersCount = request.getWinnersCount();
        }
        if(request.getMaxEntriesPerUser() != null) {
            this.maxEntriesPerUser = request.getMaxEntriesPerUser();
        }
        if(request.getEntryStartAt() != null) {
            this.entryStartAt = request.getEntryStartAt();
        }
        if(request.getEntryEndAt() != null) {
            this.entryEndAt = request.getEntryEndAt();
        }
        if(request.getRaffleDrawAt() != null) {
            this.raffleDrawAt = request.getRaffleDrawAt();
        }
    }

    public void updateStatus(RaffleStatus status) {
        if(status != null) {
            this.status = status;
        }
    }
}
