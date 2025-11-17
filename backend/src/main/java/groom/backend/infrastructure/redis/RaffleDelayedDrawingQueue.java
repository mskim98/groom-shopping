package groom.backend.infrastructure.redis;

import groom.backend.domain.raffle.entity.RaffleDrawingEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class RaffleDelayedDrawingQueue {

    private static final String DELAYED_QUEUE_KEY = "drawing:delayed:queue";

    private final RedisTemplate<String, RaffleDrawingEvent> raffleDrawingRedisTemplate;

    /**
     * 추첨 실행 예약 등록
     * Redis Sorted Set에 실행 시간을 score로 저장
     */
    public void scheduleDrawing(RaffleDrawingEvent event) {
        try {
            // 실행 시간을 epoch milliseconds로 변환 (score)
            long executeTimeScore = event.getDrawingExecutionTime()
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();

            // Redis Sorted Set에 추가
            raffleDrawingRedisTemplate.opsForZSet()
                    .add(DELAYED_QUEUE_KEY, event, executeTimeScore);

            log.info("추첨 실행 예약 등록 완료 - raffleId: {}, 실행예정시간: {}, score: {}",
                    event.getRaffleId(),
                    event.getDrawingExecutionTime(),
                    executeTimeScore);

        } catch (Exception e) {
            log.error("추첨 실행 예약 등록 실패 - raffleId: {}", event.getRaffleId(), e);
            throw new RuntimeException("추첨 실행 예약 등록 실패", e);
        }
    }

    /**
     * 실행 시간이 된 추첨 이벤트 조회
     */
    public Set<RaffleDrawingEvent> getReadyToExecuteDrawings() {
        try {
            long now = System.currentTimeMillis();

            // 현재 시간 이전의 모든 이벤트 조회
            Set<RaffleDrawingEvent> events = raffleDrawingRedisTemplate.opsForZSet()
                    .rangeByScore(DELAYED_QUEUE_KEY, 0, now);

            if (events != null && !events.isEmpty()) {
                log.info("실행 대기중인 추첨 발견: {} 건", events.size());
            }

            return events;

        } catch (Exception e) {
            log.error("실행 대기 추첨 조회 실패", e);
            return Set.of();
        }
    }

    /**
     * 처리 완료된 이벤트 제거
     */
    public void removeProcessedDrawing(RaffleDrawingEvent event) {
        try {
            Long removed = raffleDrawingRedisTemplate.opsForZSet()
                    .remove(DELAYED_QUEUE_KEY, event);

            if (removed != null && removed > 0) {
                log.info("처리 완료된 추첨 제거 - raffleId: {}", event.getRaffleId());
            }

        } catch (Exception e) {
            log.error("처리 완료 추첨 제거 실패 - raffleId: {}", event.getRaffleId(), e);
        }
    }

    /**
     * 특정 추첨 스케줄 취소
     */
    public void cancelSchedule(Long raffleId) {
        try {
            Set<RaffleDrawingEvent> allEvents = raffleDrawingRedisTemplate.opsForZSet()
                    .range(DELAYED_QUEUE_KEY, 0, -1);

            if (allEvents != null) {
                allEvents.stream()
                        .filter(event -> event.getRaffleId().equals(raffleId))
                        .forEach(event -> {
                            raffleDrawingRedisTemplate.opsForZSet()
                                    .remove(DELAYED_QUEUE_KEY, event);
                            log.info("추첨 스케줄 취소 - raffleId: {}", raffleId);
                        });
            }
        } catch (Exception e) {
            log.error("추첨 스케줄 취소 실패 - raffleId: {}", raffleId, e);
        }
    }
}
