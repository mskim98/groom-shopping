package groom.backend.interfaces.raffle.dto.response;

import groom.backend.domain.raffle.enums.RaffleStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
@Builder
public class MyRaffleEntryResponse {
    private Long raffleId;
    private Long raffleTicketId;
    private RaffleStatus status;
    private String raffleTitle;
    private LocalDateTime entryAt;
    private Boolean isWinner;
}

