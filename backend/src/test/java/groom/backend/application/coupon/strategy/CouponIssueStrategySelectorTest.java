package groom.backend.application.coupon.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import groom.backend.application.coupon.CouponAsyncIssueService;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/**
 * 선택자는 스프링 컨텍스트 없이도 검증할 수 있다
 * 검증 대상이 "빈 목록과 설정값 하나로 무엇을 고르는가" 뿐이기 때문이다
 *
 * <p>등록된 전략은 {@code stream}(기본) 과 {@code db-lock} 둘이다.
 * 없앤 {@code kafka} · {@code lua-then-lock} 을 설정에 남겨 두면 기동이 선택자의 오류로 멈춘다
 */
class CouponIssueStrategySelectorTest {

    private final List<CouponIssueStrategy> registered = List.of(
            new StreamIssueStrategy(mock(CouponAsyncIssueService.class)),
            new DbLockIssueStrategy(mock(CouponRepository.class), mock(CouponIssueRepository.class)));

    @Test
    @DisplayName("stream 과 db-lock 은 각자의 구현체를 고른다")
    void 등록된_전략명이면_해당_구현체를_반환한다() {
        CouponIssueStrategySelector stream = new CouponIssueStrategySelector(registered, "stream");
        CouponIssueStrategySelector dbLock = new CouponIssueStrategySelector(registered, "db-lock");

        assertThat(stream.current()).isInstanceOf(StreamIssueStrategy.class);
        assertThat(stream.currentName()).isEqualTo("stream");
        assertThat(dbLock.current()).isInstanceOf(DbLockIssueStrategy.class);
        assertThat(dbLock.currentName()).isEqualTo("db-lock");
    }

    @Test
    @DisplayName("kafka 와 lua-then-lock 은 선택자 오류로 기동이 멈춘다")
    void 없앤_전략명이면_생성_시점에_실패한다() {
        for (String removed : List.of("kafka", "lua-then-lock")) {
            assertThatThrownBy(() -> new CouponIssueStrategySelector(registered, removed))
                    .isInstanceOf(IllegalStateException.class)
                    // 무엇이 틀렸는지와 무엇이 가능한지를 함께 알려야 오타를 바로 고칠 수 있다
                    .hasMessageContaining(removed)
                    .hasMessageContaining("stream")
                    .hasMessageContaining("db-lock");
        }
    }

    @Test
    @DisplayName("미등록 전략명이면 생성 시점에 실패한다")
    void 미등록_전략명이면_생성_시점에_실패한다() {
        assertThatThrownBy(() -> new CouponIssueStrategySelector(registered, "does-not-exist"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist");
    }

    @Test
    @DisplayName("설정이 없을 때의 기본 전략은 stream 이다")
    void 기본값은_stream이다() throws Exception {
        Value value = CouponIssueStrategySelector.class.getConstructors()[0].getParameters()[1]
                .getAnnotation(Value.class);

        assertThat(value.value()).isEqualTo("${coupon.issue.strategy:stream}");
    }
}
