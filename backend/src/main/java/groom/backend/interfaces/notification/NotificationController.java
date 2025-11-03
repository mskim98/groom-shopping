package groom.backend.interfaces.notification;

import groom.backend.application.notification.NotificationApplicationService;
import groom.backend.domain.notification.entity.Notification;
import groom.backend.infrastructure.security.CustomUserDetails;
import groom.backend.infrastructure.sse.SseService;
import groom.backend.interfaces.notification.dto.response.NotificationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 알림 관련 API를 제공하는 Controller입니다.
 * SSE를 통한 실시간 알림 스트림과 알림 조회 API를 제공합니다.
 */
@Slf4j
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final SseService sseService;
    private final NotificationApplicationService notificationService;

    /**
     * SSE 연결을 생성하여 실시간 알림을 수신할 수 있도록 합니다.
     * 
     * @param authentication 인증 정보 (사용자 ID 추출용)
     * @return SseEmitter
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamNotifications(Authentication authentication) {
        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        Long userId = userDetails.getUser().getId();
        log.info("[SSE_STREAM_REQUEST] userId={}, email={}", userId, userDetails.getUser().getEmail());
        return sseService.createConnection(userId);
    }

    /**
     * 사용자의 모든 알림을 조회합니다.
     */
    @GetMapping
    public ResponseEntity<List<NotificationResponse>> getNotifications(Authentication authentication) {
        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        Long userId = userDetails.getUser().getId();
        List<Notification> notifications = notificationService.getNotifications(userId);
        
        List<NotificationResponse> responses = notifications.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(responses);
    }

    /**
     * 사용자의 읽지 않은 알림을 조회합니다.
     */
    @GetMapping("/unread")
    public ResponseEntity<List<NotificationResponse>> getUnreadNotifications(Authentication authentication) {
        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        Long userId = userDetails.getUser().getId();
        List<Notification> notifications = notificationService.getUnreadNotifications(userId);
        
        List<NotificationResponse> responses = notifications.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(responses);
    }

    /**
     * 알림을 읽음 처리합니다.
     */
    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<Void> markAsRead(
            @PathVariable Long notificationId,
            Authentication authentication) {
        notificationService.markAsRead(notificationId);
        return ResponseEntity.noContent().build();
    }

    private NotificationResponse toResponse(Notification notification) {
        return NotificationResponse.builder()
                .id(notification.getId())
                .currentStock(notification.getCurrentStock())
                .thresholdValue(notification.getThresholdValue())
                .message(notification.getMessage())
                .isRead(notification.getIsRead())
                .createdAt(notification.getCreatedAt())
                .userId(notification.getUserId())
                .productId(notification.getProductId())
                .build();
    }
}

