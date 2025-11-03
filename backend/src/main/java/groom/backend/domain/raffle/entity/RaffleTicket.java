package groom.backend.domain.raffle.entity;

import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class RaffleTicket {

    private Long raffleTicketId;
    private Long ticketNumber;
    private Long raffleId;
    private Long userId;
    private LocalDateTime createdAt;

    public RaffleTicket(Long raffleTicketId, Long ticketNumber, Long raffleId, Long userId, LocalDateTime createdAt) {
        this.raffleTicketId = raffleTicketId;
        this.ticketNumber = ticketNumber;
        this.raffleId = raffleId;
        this.userId = userId;
        this.createdAt = createdAt;
    }
}
