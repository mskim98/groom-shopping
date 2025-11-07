package groom.backend.interfaces.raffle.dto.request;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class RaffleEntryRequest {
    @Min(1)
    private int count;

}
