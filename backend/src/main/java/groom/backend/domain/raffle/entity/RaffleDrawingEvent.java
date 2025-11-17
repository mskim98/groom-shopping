package groom.backend.domain.raffle.entity;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder(toBuilder = false)
public class RaffleDrawingEvent {

    private Long raffleId;
    private LocalDateTime drawingExecutionTime;   // 추첨이 실제로 진행된 시간
    private LocalDateTime registeredAt; // 이벤트가 생성된 시간
}
