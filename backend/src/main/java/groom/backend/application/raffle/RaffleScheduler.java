package groom.backend.application.raffle;

import groom.backend.domain.raffle.entity.Raffle;
import groom.backend.domain.raffle.entity.RaffleDrawingEvent;
import groom.backend.domain.raffle.enums.RaffleStatus;
import groom.backend.domain.raffle.repository.RaffleRepository;
import groom.backend.infrastructure.kafka.raffle.DrawingEventProducer;
import groom.backend.infrastructure.redis.RaffleDelayedDrawingQueue;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class RaffleScheduler {

    private final RaffleRepository raffleRepository;
    private final RaffleApplicationService raffleService; // 상태 변경 + 이벤트 발행을 담당 (트랜잭션 내부 처리)
    private final RaffleDelayedDrawingQueue raffleDelayedDrawingQueue;
    private final DrawingEventProducer eventProducer;

    private static final int PAGE_SIZE = 100; // 적절한 청크 크기로 조정

    @PostConstruct
    public void detectDuplicateInstance() {
        log.info("RaffleScheduler initialized, instanceHash={} ", System.identityHashCode(this));
    }

    @Scheduled(cron = "30 0 0 * * *") // 매일 자정 30초에 실행
    //@Scheduled(cron = "0 */5 * * * *") // 테스트 용도로 5분마다 실행
    public void changeRaffleStatusToActive() {
        LocalDateTime now = LocalDateTime.now();
        int page = 0;

        Page<Raffle> chunk;
        do {
            PageRequest pr = PageRequest.of(page, PAGE_SIZE);

            chunk = raffleRepository.findByStatusAndEntryStartAtBefore(RaffleStatus.READY, now, pr);
            chunk.getContent().forEach(raffle -> {
                try {
                    raffleService.updateRaffleStatus(raffle, RaffleStatus.ACTIVE);
                } catch (Exception e) {
                    // 로깅 및 예외 처리
                    log.error("Error activating raffle ID {}: {}", raffle.getRaffleId(), e.getMessage());
                }
            });

            page++;
        } while (!chunk.isLast());
    }

    @Scheduled(cron = "0 1 0 * * *") // 매일 자정 1분에 실행
    //@Scheduled(cron = "0 */5 * * * *")
    public void changeRaffleStatusToClosed() {
        LocalDateTime now = LocalDateTime.now();
        int page = 0;

        Page<Raffle> chunk;
        do {
            PageRequest pr = PageRequest.of(page, PAGE_SIZE);

            chunk = raffleRepository.findAllByStatusAndEntryEndAtBefore(RaffleStatus.ACTIVE, now, pr);
            chunk.getContent().forEach(raffle -> {
                try {
                    // 1. 기존 마감 처리 로직
                    raffleService.updateRaffleStatus(raffle, RaffleStatus.CLOSED);

                    // 2. Redis에 추첨 실행 예약 등록
                    RaffleDrawingEvent event = RaffleDrawingEvent.builder()
                            .raffleId(raffle.getRaffleId())
                            .drawingExecutionTime(raffle.getRaffleDrawAt())
                            .registeredAt(LocalDateTime.now())
                            .build();

                    raffleDelayedDrawingQueue.scheduleDrawing(event);

                    log.info("추첨 마감 및 실행 예약 완료 - raffleId: {}, 실행예정: {}",
                            raffle.getRaffleId(),
                            event.getDrawingExecutionTime());
                } catch (Exception e) {
                    // 로깅 및 예외 처리
                    log.error("Error closed raffle ID {}: {}", raffle.getRaffleId(), e.getMessage());
                }
            });

            page++;
        } while (!chunk.isLast());
    }

    /**
     * 1분 마다 Redis를 체크해서 실행 시간이 된 추첨을 Kafka로 발행
     */
    @Scheduled(fixedDelay = 6000000, initialDelay = 10000)
    public void processScheduledDrawings() {
        log.debug("지연된 추첨 처리 시작");

        try {
            Set<RaffleDrawingEvent> readyEvents = raffleDelayedDrawingQueue.getReadyToExecuteDrawings();

            if (readyEvents == null || readyEvents.isEmpty()) {
                return;
            }

            log.info("처리할 추첨 발견: {} 건", readyEvents.size());

            // 각 이벤트를 Kafka로 발행
            for (RaffleDrawingEvent event : readyEvents) {
                // Kafka로 추첨 실행 메시지 발행
                eventProducer.publishRaffleDrawingEvent(event)
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                log.error("비동기 발행 실패 - raffleId={} (Redis 유지, 재시도 대상)", event.getRaffleId(), ex);
                                return;
                            }
                            try {
                                // Redis에서 제거
                                raffleDelayedDrawingQueue.removeProcessedDrawing(event);
                                log.info("추첨 실행 메시지 발행 및 Redis 제거 완료 - raffleId: {}",
                                        event.getRaffleId());
                            } catch (Exception e) {
                                log.error("추첨 실행 후 Redis 제거 실패 - raffleId: {} (수동 점검 필요)",
                                        event.getRaffleId(), e);
                            }
                        });

            }
        } catch (Exception e) {
            log.error("지연된 추첨 처리 중 오류 발생", e);
        }
    }

}
