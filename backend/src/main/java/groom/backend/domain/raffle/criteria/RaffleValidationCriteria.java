package groom.backend.domain.raffle.criteria;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

@Getter
@Builder
@RequiredArgsConstructor
@AllArgsConstructor
public class RaffleValidationCriteria {
    private UUID raffleProductId;
    private UUID winnerProductId;
    private int winnerCount;

}
