package groom.backend.interfaces.raffle.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import groom.backend.domain.raffle.enums.RaffleStatus;
import jakarta.validation.constraints.*;
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
public class RaffleUpdateRequest {
    private UUID raffleProductId;
    private UUID winnerProductId;
    @Size(max=100)
    private String title;
    @Size(max= 2000)
    private String description;
    private RaffleStatus status;
    @Min(1)
    private Integer winnersCount;
    @Min(1)
    private Integer maxEntriesPerUser;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime entryStartAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime entryEndAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime raffleDrawAt;
}
