package groom.backend.domain.raffle.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class RaffleTicket {

    private Long raffleTicketId;
    private Long ticketNumber;
    private Long raffleId;
    private Long userId;
    private LocalDateTime createdAt;


}
