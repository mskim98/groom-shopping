package groom.backend.interfaces.notification.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 여러 알림을 일괄 삭제하기 위한 요청 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "알림 일괄 삭제 요청 DTO")
public class BatchDeleteNotificationRequest {
    @NotEmpty(message = "알림 ID 목록은 필수입니다")
    @Schema(description = "삭제할 알림 ID 목록", example = "[1, 2, 3]", required = true)
    private List<Long> notificationIds;
}


