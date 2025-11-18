package groom.backend.domain.raffle.entity;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@JsonDeserialize(builder = RaffleDrawingEvent.RaffleDrawingEventBuilder.class)
@Value
@Builder(toBuilder = false)
public class RaffleDrawingEvent {

    private Long raffleId;
    private LocalDateTime drawingExecutionTime;   // 추첨이 실제로 진행된 시간
    private LocalDateTime registeredAt; // 이벤트가 생성된 시간

    @JsonPOJOBuilder(withPrefix = "")
    public static class RaffleDrawingEventBuilder {
        // Lombok이 생성한 빌더를 Jackson에서 사용하도록 빈 클래스로 둡니다.
    }
}
