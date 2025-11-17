package groom.backend.domain.raffle.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RaffleDrawingEvent {

    private Long raffleId;
    private LocalDateTime drawingExecutionTime;   // 추첨이 실제로 진행된 시간
    private LocalDateTime registeredAt; // 이벤트가 생성된 시간
}
