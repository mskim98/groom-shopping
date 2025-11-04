package groom.backend.domain.raffle.entity;

import groom.backend.domain.raffle.enums.RaffleWinnerStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class RaffleWinner {

    private Long raffleWinnerId;
    private RaffleWinnerStatus status;
    private Long raffleTicketId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;


}
