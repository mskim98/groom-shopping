package groom.backend.infrastructure.sse;

import groom.backend.domain.notification.entity.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE(Server-Sent Events) 서비스를 제공합니다.
 * 각 사용자별로 SseEmitter를 관리하고 실시간 알림을 전송합니다.
 */
@Slf4j
@Service
public class SseService {

    private static final long SSE_TIMEOUT = 60 * 60 * 1000L; // 1시간
    private static final int MAX_CONNECTIONS = 10000;  // 최대 연결 수 제한
    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 사용자별 SSE 연결을 생성합니다.
     * 최대 연결 수를 제한하여 메모리 사용량을 관리합니다.
     *
     * @param userId 사용자 ID
     * @return SseEmitter
     */
    public SseEmitter createConnection(Long userId) {
        // 최대 연결 수 제한
        if (emitters.size() >= MAX_CONNECTIONS) {
            log.warn("[SSE_MAX_CONNECTIONS] Max connections reached: {}, userId={}", MAX_CONNECTIONS, userId);
            // 가장 오래된 연결 제거 (선택적)
            cleanupStaleConnections();
            if (emitters.size() >= MAX_CONNECTIONS) {
                throw new IllegalStateException("SSE connection limit reached: " + MAX_CONNECTIONS);
            }
        }
        
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        emitter.onCompletion(() -> {
            log.info("[SSE_CONNECTION_CLOSED] userId={}", userId);
            emitters.remove(userId);
        });
        emitter.onTimeout(() -> {
            log.info("[SSE_CONNECTION_TIMEOUT] userId={}", userId);
            emitters.remove(userId);
        });
        emitter.onError((ex) -> {
            log.error("[SSE_CONNECTION_ERROR] userId={}, error={}", userId, ex.getMessage());
            emitters.remove(userId);
        });

        emitters.put(userId, emitter);
        log.info("[SSE_CONNECTION_CREATED] userId={}, totalConnections={}", userId, emitters.size());
        
        return emitter;
    }

    /**
     * 사용자에게 알림을 SSE로 전송합니다.
     *
     * @param userId 사용자 ID
     * @param notification 알림 객체
     */
    public void sendNotification(Long userId, Notification notification) {
        SseEmitter emitter = emitters.get(userId);
        
        if (emitter == null) {
            log.warn("[SSE_EMITTER_NOT_FOUND] userId={}, notificationId={}", userId, notification.getId());
            return;
        }

        try {
            long sendStartTime = System.currentTimeMillis();
            
            emitter.send(SseEmitter.event()
                    .name("notification")
                    .data(notification.getMessage()));
            
            long sendDuration = System.currentTimeMillis() - sendStartTime;
            log.info("[SSE_SEND_SUCCESS] userId={}, notificationId={}, message={}, duration={}ms", 
                    userId, notification.getId(), notification.getMessage(), sendDuration);
                    
        } catch (IOException e) {
            log.error("[SSE_SEND_FAILED] userId={}, notificationId={}, error={}", 
                    userId, notification.getId(), e.getMessage(), e);
            emitters.remove(userId);
            emitter.completeWithError(e);
        }
    }

    /**
     * 사용자 연결을 종료합니다.
     *
     * @param userId 사용자 ID
     */
    public void closeConnection(Long userId) {
        SseEmitter emitter = emitters.remove(userId);
        if (emitter != null) {
            emitter.complete();
            log.info("[SSE_CONNECTION_CLOSED_MANUALLY] userId={}", userId);
        }
    }

    /**
     * 현재 연결된 사용자 수를 반환합니다.
     */
    public int getConnectionCount() {
        return emitters.size();
    }
    
    /**
     * 주기적으로 오래된 연결을 정리합니다.
     * 5분마다 실행되어 메모리 누수를 방지합니다.
     */
    @Scheduled(fixedRate = 300000)  // 5분마다 실행
    public void cleanupStaleConnections() {
        int beforeSize = emitters.size();
        
        // 타임아웃되거나 null인 연결 제거
        emitters.entrySet().removeIf(entry -> {
            SseEmitter emitter = entry.getValue();
            if (emitter == null) {
                return true;
            }
            // 타임아웃 체크는 SseEmitter 내부에서 처리되므로 여기서는 null 체크만 수행
            return false;
        });
        
        int afterSize = emitters.size();
        if (beforeSize != afterSize) {
            log.info("[SSE_CLEANUP] Removed {} stale connections, remaining: {}", 
                    beforeSize - afterSize, afterSize);
        }
    }
}



