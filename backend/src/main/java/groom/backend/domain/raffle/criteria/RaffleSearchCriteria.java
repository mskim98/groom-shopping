package groom.backend.domain.raffle.criteria;


import groom.backend.domain.raffle.enums.RaffleStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RaffleSearchCriteria {
    private String title;
    private String raffleProductId;
    private RaffleStatus status;
    private LocalDateTime entryStartFrom;
    private LocalDateTime entryStartTo;
    private LocalDateTime raffleDrawFrom;
    private LocalDateTime raffleDrawTo;
}
