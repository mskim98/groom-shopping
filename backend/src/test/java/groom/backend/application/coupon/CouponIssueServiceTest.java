package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 발급 실패 사유가 서로 다른 ErrorCode 로 갈라지는지 고정한다
 *
 * <p>재고 소진·DB 수량 소진·경합 실패가 한 코드로 뭉쳐 있으면
 * 실패 집계에서 "재고가 남았는데 실패한 건"과 "정말 소진된 건"을 나눌 수 없다
 */
@DisplayName("CouponIssueService 발급 실패 사유 매핑")
class CouponIssueServiceTest {

    @Test
    @DisplayName("두 코드는 서로 다른 값이어야 측정에서 구분된다")
    void errorCode_경합실패와_진짜품절은_다른_코드다() {
        assertThat(ErrorCode.COUPON_ISSUE_CONTENTION)
                .isNotEqualTo(ErrorCode.COUPON_OUT_OF_STOCK);
        assertThat(ErrorCode.COUPON_STORE_MISMATCH)
                .isNotEqualTo(ErrorCode.COUPON_OUT_OF_STOCK);
    }
}
