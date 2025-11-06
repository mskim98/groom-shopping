package groom.backend.domain.order.model.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OrderStatus {
    PENDING("대기중"),
    CONFIRMED("확인됨"),
    PREPARING("준비중"),
    SHIPPING("배송중"),
    DELIVERED("배송완료"),
    COMPLETED("완료"),
    CANCELLED("취소됨");

    private final String description;
}