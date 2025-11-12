package groom.backend.interfaces.notification;

import groom.backend.application.notification.NotificationApplicationService;
import groom.backend.domain.notification.entity.Notification;
import groom.backend.infrastructure.security.CustomUserDetails;
import groom.backend.infrastructure.sse.SseService;
import groom.backend.interfaces.notification.dto.request.BatchDeleteNotificationRequest;
import groom.backend.interfaces.notification.dto.response.NotificationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@RequestMapping("/v1/notification")
@RequiredArgsConstructor
@Tag(name = "Notification", description = "알림 관련 API")
@SecurityRequirement(name = "JWT")
public class NotificationController {

    private final SseService sseService;
    private final NotificationApplicationService notificationService;

    /**
     * SSE 연결을 생성하여 실시간 알림을 수신할 수 있도록 합니다.
     * 
     * @param authentication 인증 정보 (사용자 ID 추출용)
     * @return SseEmitter
     */
    @Operation(
            summary = "SSE 실시간 알림 스트림 연결",
            description = "Server-Sent Events를 통해 실시간 알림을 수신합니다. 연결 후 알림이 발생하면 자동으로 전송됩니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "SSE 연결 성공",
                    content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE)),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다.")
    })
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamNotifications(
            @Parameter(hidden = true) Authentication authentication) {
        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        Long userId = userDetails.getUser().getId();
        log.info("[SSE_STREAM_REQUEST] userId={}, email={}", userId, userDetails.getUser().getEmail());
        return sseService.createConnection(userId);
    }

    /**
     * 사용자의 모든 알림을 조회합니다.
     */
    @Operation(
            summary = "모든 알림 조회",
            description = "현재 로그인한 사용자의 모든 알림을 조회합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "알림 조회 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = NotificationResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다.")
    })
    @GetMapping
    public ResponseEntity<List<NotificationResponse>> getNotifications(
            @Parameter(hidden = true) Authentication authentication) {
        try {
            CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
            Long userId = userDetails.getUser().getId();
            log.info("[GET_NOTIFICATIONS] userId={}", userId);
            
            List<Notification> notifications = notificationService.getNotifications(userId);
            log.info("[GET_NOTIFICATIONS] notificationCount={}", notifications.size());
            
            List<NotificationResponse> responses = notifications.stream()
                    .filter(notification -> notification != null)
                    .map(this::toResponse)
                    .filter(response -> response != null)
                    .collect(Collectors.toList());
            
            log.info("[GET_NOTIFICATIONS_SUCCESS] userId={}, responseCount={}", userId, responses.size());
            return ResponseEntity.ok(responses);
        } catch (Exception e) {
            log.error("[GET_NOTIFICATIONS_ERROR]", e);
            throw e;
        }
    }

    /**
     * 사용자의 읽지 않은 알림을 조회합니다.
     */
    @Operation(
            summary = "읽지 않은 알림 조회",
            description = "현재 로그인한 사용자의 읽지 않은 알림만 조회합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "읽지 않은 알림 조회 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = NotificationResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다.")
    })
    @GetMapping("/unread")
    public ResponseEntity<List<NotificationResponse>> getUnreadNotifications(
            @Parameter(hidden = true) Authentication authentication) {
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
    @Operation(
            summary = "알림 읽음 처리",
            description = "특정 알림을 읽음 처리합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "읽음 처리 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다."),
            @ApiResponse(responseCode = "404", description = "알림을 찾을 수 없음")
    })
    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<Void> markAsRead(
            @Parameter(description = "알림 ID", required = true, example = "1")
            @PathVariable Long notificationId,
            @Parameter(hidden = true) Authentication authentication) {
        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        Long userId = userDetails.getUser().getId();
        notificationService.markAsRead(notificationId, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 사용자의 모든 알림을 읽음 처리합니다.
     */
    @Operation(
            summary = "모든 알림 읽음 처리",
            description = "현재 로그인한 사용자의 모든 읽지 않은 알림을 읽음 처리합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "모든 알림 읽음 처리 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다.")
    })
    @PatchMapping("/read-all")
    public ResponseEntity<Void> markAllAsRead(
            @Parameter(hidden = true) Authentication authentication) {
        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        Long userId = userDetails.getUser().getId();
        notificationService.markAllAsRead(userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 알림을 삭제합니다.
     */
    @Operation(
            summary = "알림 삭제",
            description = "특정 알림을 삭제합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "알림 삭제 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다."),
            @ApiResponse(responseCode = "404", description = "알림을 찾을 수 없음")
    })
    @DeleteMapping("/{notificationId}")
    public ResponseEntity<Void> deleteNotification(
            @Parameter(description = "알림 ID", required = true, example = "1")
            @PathVariable Long notificationId,
            @Parameter(hidden = true) Authentication authentication) {
        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        Long userId = userDetails.getUser().getId();
        notificationService.deleteNotification(notificationId, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 여러 알림을 일괄 삭제합니다.
     */
    @Operation(
            summary = "알림 일괄 삭제",
            description = "여러 알림을 한 번에 삭제합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "알림 일괄 삭제 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다.")
    })
    @DeleteMapping("/batch")
    public ResponseEntity<Void> deleteNotifications(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "삭제할 알림 ID 목록",
                    required = true,
                    content = @Content(schema = @Schema(implementation = BatchDeleteNotificationRequest.class))
            )
            @RequestBody BatchDeleteNotificationRequest request,
            @Parameter(hidden = true) Authentication authentication) {
        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        Long userId = userDetails.getUser().getId();
        notificationService.deleteNotifications(request.getNotificationIds(), userId);
        return ResponseEntity.noContent().build();
    }

    private NotificationResponse toResponse(Notification notification) {
        try {
            if (notification == null) {
                log.warn("[TO_RESPONSE] notification is null");
                return null;
            }
            
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
        } catch (Exception e) {
            log.error("[TO_RESPONSE_ERROR] notificationId={}, error={}", 
                    notification != null ? notification.getId() : "null", e.getMessage(), e);
            throw e;
        }
    }
}



