package groom.backend.interfaces.raffle.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class WinnersListResponse {
    private Long raffleId;
    private String raffleTitle;
    private LocalDateTime drawAt;
    private int winnersCount;
    private List<WinnerDto> winners;
}
