package groom.backend.interfaces.raffle.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class RaffleDrawCondition {
    private Long raffleId;
    private Integer numberOfWinners;
}
