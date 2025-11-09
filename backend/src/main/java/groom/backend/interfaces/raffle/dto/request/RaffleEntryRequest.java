package groom.backend.interfaces.raffle.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "추첨 참여 요청 DTO")
public class RaffleEntryRequest {
    @Schema(description = "참여 티켓 수", example = "1", required = true, minimum = "1")
    @Min(1)
    private int count;

}
