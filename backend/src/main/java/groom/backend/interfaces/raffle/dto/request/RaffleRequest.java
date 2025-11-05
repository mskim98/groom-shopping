package groom.backend.interfaces.raffle.dto.request;

import groom.backend.domain.raffle.enums.RaffleStatus;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RaffleRequest {
    private UUID raffleProductId;
    private UUID winnerProductId;
    @NotBlank
    private String title;
    private String description;
    private RaffleStatus status;
    private int winnersCount;
    private int maxEntriesPerUser;
    private LocalDateTime entryStartAt;
    private LocalDateTime entryEndAt;
    private LocalDateTime raffleDrawAt;
}
