package groom.backend.domain.raffle.entity;

import groom.backend.domain.raffle.enums.RaffleStatus;
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
}
