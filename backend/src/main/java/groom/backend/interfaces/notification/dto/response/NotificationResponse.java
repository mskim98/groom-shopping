package groom.backend.interfaces.notification.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "알림 응답 DTO")
public class NotificationResponse {
    @Schema(description = "알림 ID", example = "1")
    private Long id;
    
    @Schema(description = "현재 재고", example = "10")
    private Integer currentStock;
    
    @Schema(description = "임계값", example = "5")
    private Integer thresholdValue;
    
    @Schema(description = "알림 메시지", example = "재고가 10개로 얼마 남지 않았어요")
    private String message;
    
    @Schema(description = "읽음 여부", example = "false")
    private Boolean isRead;
    
    @Schema(description = "생성 일시", example = "2024-01-01T12:00:00")
    private LocalDateTime createdAt;
    
    @Schema(description = "사용자 ID", example = "1")
    private Long userId;
    
    @Schema(description = "제품 ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID productId;
}


