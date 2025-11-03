package groom.backend.domain.raffle.entity;

import groom.backend.domain.raffle.enums.RaffleWinnerStatus;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class RaffleWinner {

    private Long raffleWinnerId;
    private RaffleWinnerStatus status;
    private Long raffleTicketId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public RaffleWinner(Long raffleWinnerId, RaffleWinnerStatus status, Long raffleTicketId, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.raffleWinnerId = raffleWinnerId;
        this.status = status;
        this.raffleTicketId = raffleTicketId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
