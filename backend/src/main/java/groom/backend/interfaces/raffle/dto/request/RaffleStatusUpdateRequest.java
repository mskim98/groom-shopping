package groom.backend.interfaces.raffle.dto.request;

import groom.backend.domain.raffle.enums.RaffleStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RaffleStatusUpdateRequest {
    @Schema(description = "추첨 상태", example = "DRAFT, READY, ACTIVE, CLOSED, DRAWN, CANCELLED")
    private RaffleStatus status;
}