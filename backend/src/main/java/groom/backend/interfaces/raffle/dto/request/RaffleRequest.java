package groom.backend.interfaces.raffle.dto.request;

import groom.backend.domain.raffle.enums.RaffleStatus;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RaffleRequest {
    @NotBlank
    private String raffleProductId;
    @NotBlank
    private String winnerProductId;
    @NotBlank
    private String title;
    @NotBlank
    private String description;
    private RaffleStatus status = RaffleStatus.DRAFT;
    private int winnersCount;
    private int maxEntriesPerUser;
    private LocalDateTime entryStartAt;
    private LocalDateTime entryEndAt;
    private LocalDateTime raffleDrawAt;
}
