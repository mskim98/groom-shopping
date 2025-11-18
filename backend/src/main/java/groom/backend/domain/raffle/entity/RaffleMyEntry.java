package groom.backend.domain.raffle.entity;

import groom.backend.domain.raffle.enums.RaffleStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class RaffleMyEntry {
    private Long raffleTicketId;
    private Long raffleId;
    private RaffleStatus status;
    private String raffleTitle;
    private LocalDateTime entryAt;
    private Boolean isWinner;
}
