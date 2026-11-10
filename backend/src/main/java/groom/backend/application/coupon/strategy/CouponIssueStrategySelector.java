package groom.backend.application.coupon.strategy;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 설정값으로 발급 전략 하나를 고른다 - {@code stream}(기본) 또는 {@code db-lock}
 *
 * <p>고르는 시점이 기동 시점인 것이 의도다. 요청마다 고르면 한 회차 안에서 전략이 섞일 수 있고,
 * 그러면 그 회차의 측정값이 어느 전략의 것인지 말할 수 없게 된다.
 * 없앤 {@code kafka} · {@code lua-then-lock} 이 설정에 남아 있으면 기동이 이 선택자의 오류로 멈춘다
 */
@Component
public class CouponIssueStrategySelector {

    private final Map<String, CouponIssueStrategy> byName;
    private final String configured;

    public CouponIssueStrategySelector(List<CouponIssueStrategy> strategies,
                                       @Value("${coupon.issue.strategy:stream}") String configured) {
        this.byName = strategies.stream()
                .collect(Collectors.toMap(CouponIssueStrategy::name, Function.identity()));
        this.configured = configured;
        if (!byName.containsKey(configured)) {
            // 전략명 오타를 런타임 장애가 아니라 기동 실패로 드러낸다
            // 기동에 성공해 버리면 측정을 다 끝낸 뒤에야 어긋난 것을 알게 된다
            throw new IllegalStateException(
                    "등록되지 않은 발급 전략: " + configured + " / 사용 가능: " + byName.keySet());
        }
    }

    public CouponIssueStrategy current() {
        return byName.get(configured);
    }

    /**
     * 측정 스크립트가 서버의 라운드를 확인하는 데 쓴다
     */
    public String currentName() {
        return configured;
    }
}
