package groom.backend.domain.raffle.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class RaffleTicket {

    private Long raffleTicketId;
    private Long raffleId;
    private Long userId;
    private Long ticketNumber;
    private LocalDateTime createdAt;


}
