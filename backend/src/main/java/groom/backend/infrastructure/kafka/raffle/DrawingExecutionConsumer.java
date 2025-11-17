package groom.backend.infrastructure.kafka.raffle;


import groom.backend.application.raffle.RaffleDrawApplicationService;
import groom.backend.domain.raffle.entity.RaffleDrawingEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DrawingExecutionConsumer {

    private final RaffleDrawApplicationService drawingService;

    @KafkaListener(
            topics = "drawing-execute-topic",
            groupId = "drawing-executor-group",
            containerFactory = "raffleDrawingKafkaListenerContainerFactory" // 변경: 수동 ACK 팩토리 사용
    )
    public void executeDrawing(
            RaffleDrawingEvent event,
            Acknowledgment acknowledgment) {

        log.info("추첨 실행 메시지 수신 - raffleId: {}", event.getRaffleId());

        try {
            // 기존 추첨 실행 로직 호출
            drawingService.executeDrawing(event.getRaffleId());

            // 수동 커밋
            acknowledgment.acknowledge();

            // 알림은 별도 try/catch로 감싸서 실패를 격리
            try {
                drawingService.sendRaffleWinnersNotification(event.getRaffleId());
                log.info("당첨자 알림 전송 완료 - raffleId: {}", event.getRaffleId());
            } catch (Exception notifyEx) {
                log.error("당첨자 알림 전송 실패(무시) - raffleId: {}", event.getRaffleId(), notifyEx);
                // 권장: 실패한 알림을 재시도/큐/DB에 저장하는 로직 추가
            }

            log.info("추첨 실행 완료 - raffleId: {}", event.getRaffleId());

        } catch (Exception e) {
            log.error("추첨 실행 실패 - raffleId: {}", event.getRaffleId(), e);
            // acknowledge 하지 않으면 재시도됨
        }
    }
}
