package groom.backend.domain.raffle.entity;

import groom.backend.domain.raffle.enums.RaffleStatus;
import groom.backend.interfaces.raffle.dto.request.RaffleRequest;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class Raffle {

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

    public Raffle(Long raffleId, String raffleProductId, String winnerProductId, String title, String description, int winnersCount, int maxEntriesPerUser, LocalDateTime entryStartAt, LocalDateTime entryEndAt, LocalDateTime raffleDrawAt, RaffleStatus status, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.raffleId = raffleId;
        this.raffleProductId = raffleProductId;
        this.winnerProductId = winnerProductId;
        this.title = title;
        this.description = description;
        this.winnersCount = winnersCount;
        this.maxEntriesPerUser = maxEntriesPerUser;
        this.entryStartAt = entryStartAt;
        this.entryEndAt = entryEndAt;
        this.raffleDrawAt = raffleDrawAt;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public void updateRaffle(RaffleRequest request) {
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
        if(request.getWinnersCount() != 0) {
            this.winnersCount = request.getWinnersCount();
        }
        if(request.getMaxEntriesPerUser() != 0) {
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
        this.updatedAt = LocalDateTime.now();
    }
}
