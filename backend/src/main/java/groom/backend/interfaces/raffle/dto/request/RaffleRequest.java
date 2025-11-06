package groom.backend.interfaces.raffle.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import groom.backend.domain.raffle.enums.RaffleStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@RaffleDateRange
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RaffleRequest {
    @NotNull
    private UUID raffleProductId;
    @NotNull
    private UUID winnerProductId;
    @NotBlank
    @Size(max=100)
    private String title;
    @Size(max= 2000)
    private String description;
    private RaffleStatus status;
    @NotNull
    @Min(1)
    private int winnersCount;
    @Min(1)
    private int maxEntriesPerUser;
    @NotNull
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime entryStartAt;
    @NotNull
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime entryEndAt;
    @NotNull
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime raffleDrawAt;
}
