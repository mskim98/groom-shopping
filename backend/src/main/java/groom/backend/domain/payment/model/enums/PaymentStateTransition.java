package groom.backend.domain.payment.model.enums;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 결제 상태 전이 규칙 정의 - State Machine EnumMap 기반 허용 전이 테이블로 비정상 전이를 코드 수준에서 차단 결제 생명주기(PENDING -> READY -> IN_PROGRESS ->
 * DONE -> CANCELED 등등)
 */
public final class PaymentStateTransition {

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED_TRANSITIONS =
            new EnumMap<>(PaymentStatus.class);

    static {
        // PENDING: 결제가 막 생성된 상태 - 실제 결제 흐름을 준비하거나 실패/만료
        ALLOWED_TRANSITIONS.put(PaymentStatus.PENDING, EnumSet.of(
                PaymentStatus.READY,
                PaymentStatus.IN_PROGRESS,
                PaymentStatus.WAITING_FOR_DEPOSIT,
                PaymentStatus.FAILED,
                PaymentStatus.ABORTED,
                PaymentStatus.EXPIRED
        ));

        // READY: 결제창/결제정보 준비 완료 - 승인 진행, 실패, 중단, 만료
        ALLOWED_TRANSITIONS.put(PaymentStatus.READY, EnumSet.of(
                PaymentStatus.IN_PROGRESS,
                PaymentStatus.DONE,
                PaymentStatus.WAITING_FOR_DEPOSIT,
                PaymentStatus.FAILED,
                PaymentStatus.ABORTED,
                PaymentStatus.EXPIRED
        ));

        // IN_PROGRESS: 승인 API 호출 중 - 성공/실패/중단
        ALLOWED_TRANSITIONS.put(PaymentStatus.IN_PROGRESS, EnumSet.of(
                PaymentStatus.DONE,
                PaymentStatus.FAILED,
                PaymentStatus.ABORTED
        ));

        // WAITING_FOR_DEPOSIT: 가상계좌 입금 대기 - 완료/만료/중단
        ALLOWED_TRANSITIONS.put(PaymentStatus.WAITING_FOR_DEPOSIT, EnumSet.of(
                PaymentStatus.DONE,
                PaymentStatus.EXPIRED,
                PaymentStatus.ABORTED
        ));

        // DONE: 결제 완료 - 이후에는 취소/부분취소만 가능
        ALLOWED_TRANSITIONS.put(PaymentStatus.DONE, EnumSet.of(
                PaymentStatus.CANCELED,
                PaymentStatus.PARTIAL_CANCELED
        ));

        // PARTIAL_CANCELED: 부분 취소 상태에서 전액 취소만 허용
        ALLOWED_TRANSITIONS.put(PaymentStatus.PARTIAL_CANCELED, EnumSet.of(
                PaymentStatus.PARTIAL_CANCELED,
                PaymentStatus.CANCELED
        ));

        // 종료 상태: 더 이상 전이 불가
        ALLOWED_TRANSITIONS.put(PaymentStatus.CANCELED, EnumSet.noneOf(PaymentStatus.class));
        ALLOWED_TRANSITIONS.put(PaymentStatus.FAILED, EnumSet.noneOf(PaymentStatus.class));
        ALLOWED_TRANSITIONS.put(PaymentStatus.ABORTED, EnumSet.noneOf(PaymentStatus.class));
        ALLOWED_TRANSITIONS.put(PaymentStatus.EXPIRED, EnumSet.noneOf(PaymentStatus.class));
    }

    private PaymentStateTransition() {
    }

    public static boolean isAllowed(PaymentStatus from, PaymentStatus to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            return true;
        }
        Set<PaymentStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(from, EnumSet.noneOf(PaymentStatus.class));
        return allowed.contains(to);
    }

    public static void validate(PaymentStatus from, PaymentStatus to) {
        if (!isAllowed(from, to)) {
            throw new IllegalStateException(
                    String.format("허용되지 않은 결제 상태 전이입니다: %s -> %s", from, to));
        }
    }
}
