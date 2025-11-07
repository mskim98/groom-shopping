package groom.backend.interfaces.notification.dto.request;

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
public class BatchDeleteNotificationRequest {
    @NotEmpty(message = "알림 ID 목록은 필수입니다")
    private List<Long> notificationIds;
}


