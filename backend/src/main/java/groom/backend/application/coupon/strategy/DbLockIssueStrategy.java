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
 * 데이터베이스 행 잠금으로만 동시성을 해소하는 발급 경로
 *
 * <p>두 곳에서 쓴다. 설정 {@code coupon.issue.strategy=db-lock} 으로 동기 발급 엔드포인트가 이 경로를 타고,
 * Redis 가 끊기거나 {@code redis-coupon} 회로가 열리면 접수 진입점이 이 경로로 대신 발급한다
 *
 * <p>쿠폰 행 잠금을 잡은 채 {@code quantity} 와 DB 발급 수를 비교한다. {@code quantity} 는 불변 발행 수량이라
 * 감산하지 않는다 - 스트림 컨슈머도 같은 행 잠금 아래서 같은 식({@code quantity − 발급 수})을 읽으므로
 * 두 경로가 섞여 발급해도 합계가 {@code quantity} 를 넘지 않는다
 *
 * <p>이 구현의 성질
 * <ul>
 *   <li>잠금 대기가 트랜잭션 안에 있어 <b>대기하는 동안 커넥션을 계속 점유</b>한다</li>
 *   <li>따라서 동시 요청이 커넥션 풀 크기를 넘으면 풀이 마르고, 쿠폰과 무관한 기능까지 커넥션을 못 얻는다
 *       - Redis 장애 동안 느려지는 것이 이 경로의 대가다</li>
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
        return issueFor(couponId, user.getId());
    }

    /**
     * 사용자 번호만으로 발급한다. 접수 진입점이 Redis 장애 때 부른다
     */
    @Transactional
    public CouponIssueResult issueFor(Long couponId, Long userId) {
        // 행 잠금을 먼저 잡는다
        // 중복 검사를 잠금 앞에 두면 동시 요청 둘이 함께 "발급 이력 없음"을 보고 둘 다 진행한다
        Coupon coupon = couponRepository.findByIdForUpdate(couponId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));
        // 종료된 쿠폰은 발급하지 않는다. 스트림 컨슈머가 같은 쿠폰을 NOT_FOUND 로 거절하는 것과 같은 기준이다
        if (!Boolean.TRUE.equals(coupon.getIsActive())) {
            throw new BusinessException(ErrorCode.COUPON_NOT_FOUND);
        }

        if (couponIssueRepository.existsByCoupon_IdAndUserId(couponId, userId)) {
            return CouponIssueResult.alreadyIssued();
        }
        long quantity = coupon.getQuantity() == null ? 0L : coupon.getQuantity();
        if (couponIssueRepository.countByCoupon_Id(couponId) >= quantity) {
            return CouponIssueResult.soldOut();
        }

        try {
            CouponIssue saved = couponIssueRepository.save(CouponIssue.builder()
                    .coupon(coupon)
                    .userId(userId)
                    .createdAt(LocalDateTime.now())
                    .deletedAt(LocalDateTime.of(coupon.getExpireDate(), LocalTime.MIN))
                    .build());
            return CouponIssueResult.success(saved.getId());
        } catch (DataIntegrityViolationException e) {
            // uq_coupon_issue_user 위반. 위 검사를 통과했는데 여기서 막혔다면 잠금 범위가 어긋난 것이다
            log.warn("[COUPON_DUPLICATE_BLOCKED_BY_DB] strategy=db-lock couponId={}, userId={}",
                    couponId, userId);
            return CouponIssueResult.alreadyIssued();
        }
    }
}
