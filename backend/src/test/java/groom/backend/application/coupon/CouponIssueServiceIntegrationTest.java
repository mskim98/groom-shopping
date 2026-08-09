package groom.backend.application.coupon;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.auth.entity.User;
import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.model.entity.CouponIssue;
import groom.backend.domain.coupon.model.enums.CouponType;
import groom.backend.application.coupon.event.CouponIssueRequestEvent;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.domain.coupon.service.CouponCommonService;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import groom.backend.interfaces.auth.persistence.UserJpaEntity;
import groom.backend.interfaces.coupon.dto.request.CouponCreateRequest;
import groom.backend.interfaces.coupon.dto.request.CouponUpdateRequest;
import groom.backend.interfaces.coupon.dto.response.CouponIssueResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 클래스 레벨 @Transactional 을 두지 않는다
// 발급 코어가 REQUIRES_NEW 로 도는데 테스트 트랜잭션이 커밋되지 않으면 새 트랜잭션에서 쿠폰이 보이지 않는다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("CouponIssueService 통합 테스트")
class CouponIssueServiceIntegrationTest {

    @Autowired
    private CouponIssueService couponIssueService;

    @Autowired
    private CouponAsyncIssueService couponAsyncIssueService;

    @Autowired
    private CouponCommonService couponCommonService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private CouponIssueRepository couponIssueRepository;

    @Autowired
    private SpringDataUserRepository userRepository;

    @Autowired
    private CouponStockRedisRepository couponStockRedisRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    // 케이스마다 만든 쿠폰의 Redis 키를 tearDown 에서 지우기 위해 모은다
    private final List<Long> touchedCouponIds = new ArrayList<>();

    private User testUser;
    private Coupon activeCoupon;
    private Coupon inactiveCoupon;

    // 이 클래스가 만드는 사용자만 지운다. userRepository.deleteAll() 을 쓰지 않는다
    private static final List<String> FIXTURE_EMAILS = List.of("test@test.com", "test2@test.com", "other@test.com");

    // deleteAll() 은 V2 시드 사용자(admin@test.com · user_N@test.com)까지 지운다.
    // payment 는 users 로 ON DELETE CASCADE 인데 payment_compensation 에는 CASCADE 가 없어,
    // 부하 실행이 남긴 보상 레코드가 있으면 fk_payment_compensation_payment 위반으로 삭제가 막힌다.
    // 테스트가 자기 것만 정리하면 남의 데이터 상태에 결과가 흔들리지 않는다
    private void deleteFixtureUsers() {
        FIXTURE_EMAILS.forEach(email ->
                userRepository.findByEmail(email).ifPresent(userRepository::delete));
    }

    @BeforeEach
    void setUp() {
        touchedCouponIds.clear();

        // 테스트 데이터 초기화
        couponIssueRepository.deleteAll();
        couponRepository.deleteAll();
        deleteFixtureUsers();

        // 테스트 사용자 생성
        UserJpaEntity userEntity = UserJpaEntity.builder()
                .email("test@test.com")
                .password("password")
                .name("테스트 사용자")
                .role(Role.ROLE_USER)
                .grade(Grade.BRONZE)
                .build();
        userRepository.save(userEntity);
        testUser = new User(
                userEntity.getId(),
                userEntity.getEmail(),
                userEntity.getPassword(),
                userEntity.getName(),
                userEntity.getRole(),
                userEntity.getGrade(),
                userEntity.getCreatedAt(),
                userEntity.getUpdatedAt()
        );

        // 활성화된 쿠폰 생성
        CouponCreateRequest activeRequest = CouponCreateRequest.builder()
                .name("활성 쿠폰")
                .description("활성화된 쿠폰")
                .quantity(100L)
                .amount(1000)
                .type(CouponType.DISCOUNT)
                .expireDate(LocalDate.now().plusDays(30))
                .build();
        activeCoupon = couponRepository.save(activeRequest.toEntity());

        // 비활성화된 쿠폰 생성
        CouponCreateRequest inactiveRequest = CouponCreateRequest.builder()
                .name("비활성 쿠폰")
                .description("비활성화된 쿠폰")
                .quantity(50L)
                .amount(500)
                .type(CouponType.DISCOUNT)
                .expireDate(LocalDate.now().plusDays(30))
                .build();
        Coupon savedInactiveCoupon = couponRepository.save(inactiveRequest.toEntity());
        
        // 쿠폰을 비활성화
        CouponUpdateRequest updateRequest = CouponUpdateRequest.builder()
                .isActive(false)
                .build();
        savedInactiveCoupon.update(updateRequest);
        inactiveCoupon = couponRepository.save(savedInactiveCoupon);

        warmUp(activeCoupon);
        warmUp(inactiveCoupon);
    }

    @AfterEach
    void tearDown() {
        // 발급자 SET 을 남기면 다음 케이스가 COUPON_ALREADY_ISSUED 로 깨진다
        for (Long id : touchedCouponIds) {
            redisTemplate.delete(List.of(
                    CouponStockRedisRepository.stockKey(id),
                    CouponStockRedisRepository.issuedUsersKey(id)));
        }
    }

    // 운영에서는 부팅 워밍업·생성 시 워밍업이 재고를 심는다
    // 심지 않으면 모든 케이스가 DB 비관적 락 폴백을 타서 Lua 중복 검사를 검증하지 못한다
    private void warmUp(Coupon coupon) {
        touchedCouponIds.add(coupon.getId());
        couponStockRedisRepository.initStock(coupon.getId(), coupon.getQuantity());
    }

    // 접수 게이트 + 컨슈머 확정을 한 호출로 묶는다
    // 실제 컨슈머는 Kafka 를 거치지만, 이 테스트가 검증하는 것은 발급 판정이지 전달 경로가 아니다
    //
    // setUp 이 모든 쿠폰을 워밍업하므로 게이트는 SUCCESS 이고 gateReserved=true 다
    // 워밍업하지 않은 쿠폰을 다루는 케이스는 이 헬퍼를 쓰지 않는다
    private CouponIssueResponse issue(Long couponId, User user) {
        String requestId = couponAsyncIssueService.enqueue(couponId, user.getId());
        couponAsyncIssueService.process(
                new CouponIssueRequestEvent(requestId, couponId, user.getId(), true));
        String status = couponAsyncIssueService.getStatus(requestId);
        if (!"SUCCESS".equals(status)) {
            throw new IllegalStateException("발급 실패: " + status);
        }
        return couponIssueService.searchMyCoupon(user.getId()).stream()
                .filter(r -> r.getCouponId().equals(couponId))
                .findFirst().orElseThrow();
    }

    // 활성 쿠폰을 정상적으로 발급하는 시나리오 테스트
    // 접수 게이트 + 컨슈머 확정이 CouponIssue 를 생성하고 수량 감소 및 응답 반환을 검증함
    // 데이터베이스 반영 후 CouponIssue와 Coupon 수량이 올바르게 변경되었는지 확인
    @Test
    @DisplayName("쿠폰 발급 성공")
    void issueCoupon_success() {
        // when
        CouponIssueResponse response = issue(activeCoupon.getId(), testUser);

        // then
        assertThat(response).isNotNull();
        assertThat(response.getCouponIssueId()).isNotNull();
        assertThat(response.getCouponId()).isEqualTo(activeCoupon.getId());
        assertThat(response.getCreatedAt()).isNotNull();
        assertThat(response.getDeletedAt()).isNotNull();

        // DB에서 확인
        CouponIssue savedIssue = couponIssueRepository.findById(response.getCouponIssueId()).orElseThrow();
        assertThat(savedIssue.getUserId()).isEqualTo(testUser.getId());
        assertThat(savedIssue.getCoupon().getId()).isEqualTo(activeCoupon.getId());
        assertThat(savedIssue.getIsActive()).isTrue();

        // 쿠폰 수량이 감소했는지 확인
        Coupon updatedCoupon = couponRepository.findById(activeCoupon.getId()).orElseThrow();
        assertThat(updatedCoupon.getQuantity()).isEqualTo(99L);
    }

    // 존재하지 않는 쿠폰 ID로 발급 요청 시 실패하는지 테스트
    // 재고 키가 없으므로 게이트는 NOT_INITIALIZED 로 통과시키고, DB 폴백이 COUPON_NOT_FOUND 로 판정한다
    @Test
    @DisplayName("쿠폰 발급 실패 - 존재하지 않는 쿠폰")
    void issueCoupon_notFound() {
        // given
        Long nonExistentId = 999L;
        String requestId = couponAsyncIssueService.enqueue(nonExistentId, testUser.getId());

        // when - 게이트가 판정하지 못했으므로 gateReserved=false
        couponAsyncIssueService.process(
                new CouponIssueRequestEvent(requestId, nonExistentId, testUser.getId(), false));

        // then
        assertThat(couponAsyncIssueService.getStatus(requestId))
                .isEqualTo("FAILED:" + ErrorCode.COUPON_NOT_FOUND.name());
    }

    // 비활성화된 쿠폰에 대한 발급 요청 시 실패하는 시나리오
    // 게이트는 통과한다(재고 50 워밍업됨). isActive=false 는 확정 단계에서 걸린다
    @Test
    @DisplayName("쿠폰 발급 실패 - 비활성화된 쿠폰")
    void issueCoupon_inactiveCoupon() {
        // given
        String requestId = couponAsyncIssueService.enqueue(inactiveCoupon.getId(), testUser.getId());

        // when
        couponAsyncIssueService.process(new CouponIssueRequestEvent(
                requestId, inactiveCoupon.getId(), testUser.getId(), true));

        // then
        assertThat(couponAsyncIssueService.getStatus(requestId))
                .isEqualTo("FAILED:" + ErrorCode.COUPON_NOT_FOUND.name());
    }

    // 쿠폰 수량이 1개뿐인 경우, 첫 번째 발급 후 두 번째 발급 시 실패하는 테스트
    // 수량 소진 시 ErrorCode.COUPON_OUT_OF_STOCK 예외 발생과 메시지 검증
    @Test
    @DisplayName("쿠폰 발급 실패 - 수량 부족")
    void issueCoupon_quantityExhausted() {
        // given
        CouponCreateRequest request = CouponCreateRequest.builder()
                .name("수량 1 쿠폰")
                .description("수량이 1개인 쿠폰")
                .quantity(1L)
                .amount(1000)
                .type(CouponType.DISCOUNT)
                .expireDate(LocalDate.now().plusDays(30))
                .build();
        Coupon limitedCoupon = couponRepository.save(request.toEntity());
        warmUp(limitedCoupon);

        // 첫 번째 발급 성공
        issue(limitedCoupon.getId(), testUser);

        // 두 번째 사용자 생성
        UserJpaEntity userEntity2 = UserJpaEntity.builder()
                .email("test2@test.com")
                .password("password")
                .name("테스트 사용자2")
                .role(Role.ROLE_USER)
                .grade(Grade.BRONZE)
                .build();
        userRepository.save(userEntity2);
        User testUser2 = new User(
                userEntity2.getId(),
                userEntity2.getEmail(),
                userEntity2.getPassword(),
                userEntity2.getName(),
                userEntity2.getRole(),
                userEntity2.getGrade(),
                userEntity2.getCreatedAt(),
                userEntity2.getUpdatedAt()
        );

        // when & then - 두 번째는 접수 게이트에서 끊긴다
        assertThatThrownBy(() -> couponAsyncIssueService.enqueue(limitedCoupon.getId(), testUser2.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.COUPON_OUT_OF_STOCK);
                    assertThat(businessException.getMessage()).contains("수량이 소진되었습니다");
                });
    }

    // 동일 사용자가 같은 쿠폰을 중복 발급하려 할 때 실패해야 하는 테스트
    // 첫 발급 후 두 번째 발급 시 BusinessException(COUPON_ALREADY_ISSUED) 발생 확인
    @Test
    @DisplayName("쿠폰 발급 실패 - 중복 발급")
    void issueCoupon_duplicateIssue() {
        // given
        issue(activeCoupon.getId(), testUser);

        // when & then - 같은 사용자의 두 번째 요청은 발급자 SET 에 걸려 접수에서 끊긴다
        assertThatThrownBy(() -> couponAsyncIssueService.enqueue(activeCoupon.getId(), testUser.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.COUPON_ALREADY_ISSUED);
                    assertThat(businessException.getMessage()).contains("이미 발급받은 쿠폰입니다");
                });
    }

    // 사용자의 보유 쿠폰 목록을 정상적으로 조회하는 테스트
    // 다수의 쿠폰 발급 후 해당 사용자 기준으로 2개 쿠폰만 반환되는지 확인
    @Test
    @DisplayName("내 쿠폰 조회 성공")
    void searchMyCoupon_success() {
        // given
        issue(activeCoupon.getId(), testUser);

        // 다른 쿠폰 생성 및 발급
        CouponCreateRequest request2 = CouponCreateRequest.builder()
                .name("다른 쿠폰")
                .description("다른 쿠폰 설명")
                .quantity(50L)
                .amount(500)
                .type(CouponType.PERCENT)
                .expireDate(LocalDate.now().plusDays(30))
                .build();
        Coupon coupon2 = couponRepository.save(request2.toEntity());
        warmUp(coupon2);
        issue(coupon2.getId(), testUser);

        // 다른 사용자 생성 및 쿠폰 발급
        UserJpaEntity userEntity2 = UserJpaEntity.builder()
                .email("other@test.com")
                .password("password")
                .name("다른 사용자")
                .role(Role.ROLE_USER)
                .grade(Grade.BRONZE)
                .build();
        userRepository.save(userEntity2);
        User otherUser = new User(
                userEntity2.getId(),
                userEntity2.getEmail(),
                userEntity2.getPassword(),
                userEntity2.getName(),
                userEntity2.getRole(),
                userEntity2.getGrade(),
                userEntity2.getCreatedAt(),
                userEntity2.getUpdatedAt()
        );
        issue(activeCoupon.getId(), otherUser);

        // when
        List<CouponIssueResponse> response = couponIssueService.searchMyCoupon(testUser.getId());

        // then
        assertThat(response).isNotNull();
        assertThat(response.size()).isEqualTo(2);
        assertThat(response).extracting("couponId")
                .containsExactlyInAnyOrder(activeCoupon.getId(), coupon2.getId());
    }

    // 만료된 쿠폰을 발급받은 사용자가 조회할 때, 만료 쿠폰이 결과에서 제외되는지 검증
    // expireDate가 과거인 쿠폰은 제외되어야 함
    @Test
    @DisplayName("내 쿠폰 조회 - 만료된 쿠폰은 제외")
    void searchMyCoupon_excludeExpired() {
        // given
        CouponCreateRequest expiredRequest = CouponCreateRequest.builder()
                .name("만료 쿠폰")
                .description("만료된 쿠폰")
                .quantity(10L)
                .amount(1000)
                .type(CouponType.DISCOUNT)
                .expireDate(LocalDate.now().minusDays(1)) // 어제 만료
                .build();
        Coupon expiredCoupon = couponRepository.save(expiredRequest.toEntity());
        // 발급 서비스를 타지 않으므로 워밍업하지 않고 키 정리 대상에만 넣는다
        touchedCouponIds.add(expiredCoupon.getId());

        // 만료된 쿠폰 발급
        CouponIssue expiredIssue = CouponIssue.builder()
                .coupon(expiredCoupon)
                .userId(testUser.getId())
                .createdAt(LocalDateTime.now().minusDays(2))
                .deletedAt(LocalDateTime.of(expiredCoupon.getExpireDate(), LocalTime.MIN))
                .isActive(true)
                .build();
        couponIssueRepository.save(expiredIssue);

        // 유효한 쿠폰 발급
        issue(activeCoupon.getId(), testUser);

        // when
        List<CouponIssueResponse> response = couponIssueService.searchMyCoupon(testUser.getId());

        // then
        assertThat(response).isNotNull();
        assertThat(response.size()).isEqualTo(1);
        assertThat(response.get(0).getCouponId()).isEqualTo(activeCoupon.getId());
    }

    // 이미 사용된 쿠폰이 조회 결과에서 제외되는지 테스트
    // useCoupon() 호출 후 active=false인 쿠폰은 검색 결과에 포함되지 않아야 함
    @Test
    @DisplayName("내 쿠폰 조회 - 사용된 쿠폰은 제외")
    void searchMyCoupon_excludeUsed() {
        // given
        CouponIssueResponse issued = issue(activeCoupon.getId(), testUser);
        
        // 쿠폰 사용
        couponIssueService.useCoupon(issued.getCouponIssueId(), testUser.getId());

        // when
        List<CouponIssueResponse> response = couponIssueService.searchMyCoupon(testUser.getId());

        // then
        assertThat(response).isNotNull();
        assertThat(response.size()).isEqualTo(0);
    }

    // 할인 금액 계산 테스트 (DISCOUNT 타입)
    // 정액 할인 쿠폰의 amount 값이 그대로 반환되는지 검증
    @Test
    @DisplayName("할인 금액 계산 - DISCOUNT 타입")
    void calculateDiscount_discountType() {
        // given
        CouponIssueResponse issued = issue(activeCoupon.getId(), testUser);
        Integer cost = 10000;

        // when
        Integer discount = couponIssueService.calculateDiscount(issued.getCouponIssueId(), testUser.getId(), cost);

        // then
        assertThat(discount).isEqualTo(1000); // amount 값 그대로
    }

    // 할인 금액 계산 테스트 (PERCENT 타입)
    // 백분율 계산 및 백원 단위 절삭 로직이 올바르게 적용되는지 확인 (10% → 1200원)
    @Test
    @DisplayName("할인 금액 계산 - PERCENT 타입")
    void calculateDiscount_percentType() {
        // given
        CouponCreateRequest percentRequest = CouponCreateRequest.builder()
                .name("퍼센트 쿠폰")
                .description("10% 할인")
                .quantity(10L)
                .amount(10) // 10%
                .type(CouponType.PERCENT)
                .expireDate(LocalDate.now().plusDays(30))
                .build();
        Coupon percentCoupon = couponRepository.save(percentRequest.toEntity());
        warmUp(percentCoupon);
        CouponIssueResponse issued = issue(percentCoupon.getId(), testUser);
        Integer cost = 12500; // 10% 할인이면 1250원, 백원 단위 절삭이면 1200원

        // when
        Integer discount = couponIssueService.calculateDiscount(issued.getCouponIssueId(), testUser.getId(), cost);

        // then
        // 12500 * 0.1 = 1250, 백원 단위 절삭하면 1200
        assertThat(discount).isEqualTo(1200);
    }

    // 존재하지 않는 쿠폰 이슈 ID로 할인 금액 계산 시 실패해야 함
    // ErrorCode.COUPON_NOT_FOUND 예외 발생 검증
    @Test
    @DisplayName("할인 금액 계산 실패 - 존재하지 않는 쿠폰")
    void calculateDiscount_notFound() {
        // given
        Long nonExistentId = 999L;

        // when & then
        assertThatThrownBy(() -> couponIssueService.calculateDiscount(nonExistentId, testUser.getId(), 10000))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.COUPON_NOT_FOUND);
                });
    }

    // 다른 사용자가 소유한 쿠폰으로 할인 요청 시 실패 테스트
    // 권한 검증 로직 작동 확인 (ErrorCode.COUPON_USER_MATCH_FAILED)
    @Test
    @DisplayName("할인 금액 계산 실패 - 다른 사용자의 쿠폰")
    void calculateDiscount_otherUserCoupon() {
        // given
        UserJpaEntity userEntity2 = UserJpaEntity.builder()
                .email("other@test.com")
                .password("password")
                .name("다른 사용자")
                .role(Role.ROLE_USER)
                .grade(Grade.BRONZE)
                .build();
        userRepository.save(userEntity2);
        User otherUser = new User(
                userEntity2.getId(),
                userEntity2.getEmail(),
                userEntity2.getPassword(),
                userEntity2.getName(),
                userEntity2.getRole(),
                userEntity2.getGrade(),
                userEntity2.getCreatedAt(),
                userEntity2.getUpdatedAt()
        );

        CouponIssueResponse issued = issue(activeCoupon.getId(), otherUser);

        // when & then
        assertThatThrownBy(() -> couponIssueService.calculateDiscount(issued.getCouponIssueId(), testUser.getId(), 10000))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.COUPON_USER_MATCH_FAILED);
                    assertThat(businessException.getMessage()).contains("쿠폰 소유자와 사용자가 일치하지 않습니다");
                });
    }

    // 만료된 쿠폰으로 할인 금액 계산 요청 시 실패 테스트
    // ErrorCode.COUPON_EXPIRED 및 메시지 “쿠폰 사용일이 만료되었습니다” 확인
    @Test
    @DisplayName("할인 금액 계산 실패 - 만료된 쿠폰")
    void calculateDiscount_expiredCoupon() {
        // given
        CouponCreateRequest expiredRequest = CouponCreateRequest.builder()
                .name("만료 쿠폰")
                .description("만료된 쿠폰")
                .quantity(10L)
                .amount(1000)
                .type(CouponType.DISCOUNT)
                .expireDate(LocalDate.now().minusDays(1))
                .build();
        Coupon expiredCoupon = couponRepository.save(expiredRequest.toEntity());
        // 발급 서비스를 타지 않으므로 워밍업하지 않고 키 정리 대상에만 넣는다
        touchedCouponIds.add(expiredCoupon.getId());

        CouponIssue expiredIssue = CouponIssue.builder()
                .coupon(expiredCoupon)
                .userId(testUser.getId())
                .createdAt(LocalDateTime.now().minusDays(2))
                .deletedAt(LocalDateTime.of(expiredCoupon.getExpireDate(), LocalTime.MIN))
                .isActive(true)
                .build();
        CouponIssue saved = couponIssueRepository.save(expiredIssue);

        // when & then
        assertThatThrownBy(() -> couponIssueService.calculateDiscount(saved.getId(), testUser.getId(), 10000))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.COUPON_EXPIRED);
                    assertThat(businessException.getMessage()).contains("쿠폰 사용일이 만료되었습니다");
                });
    }

    // 쿠폰 사용 정상 흐름 테스트
    // 사용 후 CouponIssue.isActive=false, deletedAt이 갱신되는지 검증
    @Test
    @DisplayName("쿠폰 사용 성공")
    void useCoupon_success() {
        // given
        CouponIssueResponse issued = issue(activeCoupon.getId(), testUser);

        // when
        Boolean result = couponIssueService.useCoupon(issued.getCouponIssueId(), testUser.getId());

        // then
        assertThat(result).isTrue();

        // DB에서 확인
        CouponIssue usedCoupon = couponIssueRepository.findById(issued.getCouponIssueId()).orElseThrow();
        assertThat(usedCoupon.getIsActive()).isFalse();
        assertThat(usedCoupon.getDeletedAt()).isNotNull();
    }

    // 존재하지 않는 쿠폰 발급 ID로 쿠폰 사용 시 예외 발생 테스트
    // ErrorCode.COUPON_NOT_FOUND 예외 발생 검증
    @Test
    @DisplayName("쿠폰 사용 실패 - 존재하지 않는 쿠폰")
    void useCoupon_notFound() {
        // given
        Long nonExistentId = 999L;

        // when & then
        assertThatThrownBy(() -> couponIssueService.useCoupon(nonExistentId, testUser.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.COUPON_NOT_FOUND);
                });
    }

    // 다른 사용자의 쿠폰을 사용하려 할 때 실패하는 시나리오
    // ErrorCode.COUPON_USER_MATCH_FAILED 예외 및 메시지 검증
    @Test
    @DisplayName("쿠폰 사용 실패 - 다른 사용자의 쿠폰")
    void useCoupon_otherUserCoupon() {
        // given
        UserJpaEntity userEntity2 = UserJpaEntity.builder()
                .email("other@test.com")
                .password("password")
                .name("다른 사용자")
                .role(Role.ROLE_USER)
                .grade(Grade.BRONZE)
                .build();
        userRepository.save(userEntity2);
        User otherUser = new User(
                userEntity2.getId(),
                userEntity2.getEmail(),
                userEntity2.getPassword(),
                userEntity2.getName(),
                userEntity2.getRole(),
                userEntity2.getGrade(),
                userEntity2.getCreatedAt(),
                userEntity2.getUpdatedAt()
        );

        CouponIssueResponse issued = issue(activeCoupon.getId(), otherUser);

        // when & then
        assertThatThrownBy(() -> couponIssueService.useCoupon(issued.getCouponIssueId(), testUser.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.COUPON_USER_MATCH_FAILED);
                    assertThat(businessException.getMessage()).contains("쿠폰 소유자와 사용자가 일치하지 않습니다");
                });
    }

    // 이미 사용 처리된 쿠폰을 다시 사용하려 하면 실패해야 하는 테스트
    // ErrorCode.COUPON_NOT_USABLE 예외 발생 확인
    @Test
    @DisplayName("쿠폰 사용 실패 - 이미 사용된 쿠폰")
    void useCoupon_alreadyUsed() {
        // given
        CouponIssueResponse issued = issue(activeCoupon.getId(), testUser);
        couponIssueService.useCoupon(issued.getCouponIssueId(), testUser.getId());

        // when & then
        assertThatThrownBy(() -> couponIssueService.useCoupon(issued.getCouponIssueId(), testUser.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.COUPON_NOT_USABLE);
                });
    }

    // 만료된 쿠폰을 사용하려 할 때 실패 테스트
    // ErrorCode.COUPON_EXPIRED 및 메시지 “쿠폰 사용일이 만료되었습니다” 확인
    @Test
    @DisplayName("쿠폰 사용 실패 - 만료된 쿠폰")
    void useCoupon_expiredCoupon() {
        // given
        CouponCreateRequest expiredRequest = CouponCreateRequest.builder()
                .name("만료 쿠폰")
                .description("만료된 쿠폰")
                .quantity(10L)
                .amount(1000)
                .type(CouponType.DISCOUNT)
                .expireDate(LocalDate.now().minusDays(1))
                .build();
        Coupon expiredCoupon = couponRepository.save(expiredRequest.toEntity());
        // 발급 서비스를 타지 않으므로 워밍업하지 않고 키 정리 대상에만 넣는다
        touchedCouponIds.add(expiredCoupon.getId());

        CouponIssue expiredIssue = CouponIssue.builder()
                .coupon(expiredCoupon)
                .userId(testUser.getId())
                .createdAt(LocalDateTime.now().minusDays(2))
                .deletedAt(LocalDateTime.of(expiredCoupon.getExpireDate(), LocalTime.MIN))
                .isActive(true)
                .build();
        CouponIssue saved = couponIssueRepository.save(expiredIssue);

        // when & then
        assertThatThrownBy(() -> couponIssueService.useCoupon(saved.getId(), testUser.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.COUPON_EXPIRED);
                    assertThat(businessException.getMessage()).contains("쿠폰 사용일이 만료되었습니다");
                });
    }
}

