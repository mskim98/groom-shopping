package groom.backend.application.coupon.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import groom.backend.domain.auth.entity.User;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 선택자는 스프링 컨텍스트 없이도 검증할 수 있다
 * 검증 대상이 "빈 목록과 설정값 하나로 무엇을 고르는가" 뿐이기 때문이다
 */
class CouponIssueStrategySelectorTest {

    @Test
    @DisplayName("미등록 전략명이면 생성 시점에 실패한다")
    void 미등록_전략명이면_생성_시점에_실패한다() {
        List<CouponIssueStrategy> only = List.of(new FakeStrategy("kafka"));

        assertThatThrownBy(() -> new CouponIssueStrategySelector(only, "does-not-exist"))
                .isInstanceOf(IllegalStateException.class)
                // 무엇이 틀렸는지와 무엇이 가능한지를 함께 알려야 오타를 바로 고칠 수 있다
                .hasMessageContaining("does-not-exist")
                .hasMessageContaining("kafka");
    }

    @Test
    @DisplayName("등록된 전략명이면 해당 구현체를 반환한다")
    void 등록된_전략명이면_해당_구현체를_반환한다() {
        CouponIssueStrategy kafka = new FakeStrategy("kafka");
        CouponIssueStrategy dbLock = new FakeStrategy("db-lock");

        CouponIssueStrategySelector selector =
                new CouponIssueStrategySelector(List.of(kafka, dbLock), "db-lock");

        assertThat(selector.current().name()).isEqualTo("db-lock");
        assertThat(selector.currentName()).isEqualTo("db-lock");
    }

    private record FakeStrategy(String name) implements CouponIssueStrategy {
        @Override
        public CouponIssueResult issue(Long couponId, User user) {
            return CouponIssueResult.soldOut();
        }
    }
}
