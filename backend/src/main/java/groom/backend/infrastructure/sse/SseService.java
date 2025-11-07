package groom.backend.infrastructure.sse;

import groom.backend.domain.notification.entity.Notification;
import lombok.extern.slf4j.Slf4j;
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
    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 사용자별 SSE 연결을 생성합니다.
     *
     * @param userId 사용자 ID
     * @return SseEmitter
     */
    public SseEmitter createConnection(Long userId) {
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
}


