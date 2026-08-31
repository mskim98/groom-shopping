package groom.backend.application.coupon.strategy;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.auth.entity.User;
import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.model.entity.CouponIssue;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * S0 베이스라인 - 데이터베이스 행 잠금으로만 동시성을 해소한다
 *
 * <p>측정 전용이다. 이후 라운드가 비교될 원점을 만드는 것이 목적이고, 프로덕션 기본값이 아니다
 *
 * <p>이 구현의 성질이 곧 측정 대상이다
 * <ul>
 *   <li>잠금 대기가 트랜잭션 안에 있어 <b>대기하는 동안 커넥션을 계속 점유</b>한다</li>
 *   <li>따라서 동시 요청이 커넥션 풀 크기를 넘으면 풀이 마르고, 쿠폰과 무관한 기능까지 커넥션을 못 얻는다</li>
 *   <li>모든 요청이 데이터베이스까지 도달한다 - 앞에서 걸러내는 단계가 없다</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbLockIssueStrategy implements CouponIssueStrategy {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;

    @Override
    public String name() {
        return "db-lock";
    }

    @Override
    @Transactional
    public CouponIssueResult issue(Long couponId, User user) {
        // 행 잠금을 먼저 잡는다
        // 중복 검사를 잠금 앞에 두면 동시 요청 둘이 함께 "발급 이력 없음"을 보고 둘 다 진행한다
        Coupon coupon = couponRepository.findByIdForUpdate(couponId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));

        if (couponIssueRepository.existsByCoupon_IdAndUserId(couponId, user.getId())) {
            return CouponIssueResult.alreadyIssued();
        }
        if (coupon.getQuantity() <= 0) {
            return CouponIssueResult.soldOut();
        }

        coupon.decreaseQuantity();
        try {
            CouponIssue saved = couponIssueRepository.save(CouponIssue.builder()
                    .coupon(coupon)
                    .userId(user.getId())
                    .createdAt(LocalDateTime.now())
                    .deletedAt(LocalDateTime.of(coupon.getExpireDate(), LocalTime.MIN))
                    .build());
            return CouponIssueResult.success(saved.getId());
        } catch (DataIntegrityViolationException e) {
            // uq_coupon_issue_user 위반. 위 검사를 통과했는데 여기서 막혔다면 잠금 범위가 어긋난 것이다
            log.warn("[COUPON_DUPLICATE_BLOCKED_BY_DB] strategy=db-lock couponId={}, userId={}",
                    couponId, user.getId());
            return CouponIssueResult.alreadyIssued();
        }
    }
}
