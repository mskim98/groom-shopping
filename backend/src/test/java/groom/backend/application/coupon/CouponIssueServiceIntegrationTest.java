package groom.backend.application.coupon;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.auth.entity.User;
import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.model.entity.CouponIssue;
import groom.backend.domain.coupon.model.enums.CouponType;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.domain.coupon.service.CouponCommonService;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import groom.backend.interfaces.auth.persistence.UserJpaEntity;
import groom.backend.interfaces.coupon.dto.request.CouponCreateRequest;
import groom.backend.interfaces.coupon.dto.request.CouponUpdateRequest;
import groom.backend.interfaces.coupon.dto.response.CouponIssueResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("CouponIssueService 통합 테스트")
class CouponIssueServiceIntegrationTest {

    @Autowired
    private CouponIssueService couponIssueService;

    @Autowired
    private CouponCommonService couponCommonService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private CouponIssueRepository couponIssueRepository;

    @Autowired
    private SpringDataUserRepository userRepository;

    private User testUser;
    private Coupon activeCoupon;
    private Coupon inactiveCoupon;

    @BeforeEach
    void setUp() {
        // 테스트 데이터 초기화
        couponIssueRepository.deleteAll();
        couponRepository.deleteAll();
        userRepository.deleteAll();

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
    }

    // 활성 쿠폰을 정상적으로 발급하는 시나리오 테스트
    // CouponIssueService.issueCoupon()이 CouponIssue를 생성하고 수량 감소 및 응답 반환을 검증함
    // 데이터베이스 반영 후 CouponIssue와 Coupon 수량이 올바르게 변경되었는지 확인
    @Test
    @DisplayName("쿠폰 발급 성공")
    void issueCoupon_success() {
        // when
        CouponIssueResponse response = couponIssueService.issueCoupon(activeCoupon.getId(), testUser);

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

    // 존재하지 않는 쿠폰 ID로 발급 요청 시 예외가 발생하는지 테스트
    // BusinessException이 ErrorCode.NOT_FOUND와 함께 발생해야 함
    @Test
    @DisplayName("쿠폰 발급 실패 - 존재하지 않는 쿠폰")
    void issueCoupon_notFound() {
        // given
        Long nonExistentId = 999L;

        // when & then
        assertThatThrownBy(() -> couponIssueService.issueCoupon(nonExistentId, testUser))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                });
    }

    // 비활성화된 쿠폰에 대한 발급 요청 시 실패하는 시나리오
    // 활성화 상태 검증 로직이 작동하여 NOT_FOUND 예외 발생을 확인
    @Test
    @DisplayName("쿠폰 발급 실패 - 비활성화된 쿠폰")
    void issueCoupon_inactiveCoupon() {
        // when & then
        assertThatThrownBy(() -> couponIssueService.issueCoupon(inactiveCoupon.getId(), testUser))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                });
    }

    // 쿠폰 수량이 1개뿐인 경우, 첫 번째 발급 후 두 번째 발급 시 실패하는 테스트
    // 수량 소진 시 ErrorCode.CONFLICT 예외 발생과 메시지 검증
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

        // 첫 번째 발급 성공
        couponIssueService.issueCoupon(limitedCoupon.getId(), testUser);

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

        // when & then - 두 번째 발급 시도 시 실패
        assertThatThrownBy(() -> couponIssueService.issueCoupon(limitedCoupon.getId(), testUser2))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(businessException.getMessage()).contains("수량이 소진되었습니다");
                });
    }

    // 동일 사용자가 같은 쿠폰을 중복 발급하려 할 때 실패해야 하는 테스트
    // 첫 발급 후 두 번째 발급 시 BusinessException(CONFLICT) 발생 확인
    @Test
    @DisplayName("쿠폰 발급 실패 - 중복 발급")
    void issueCoupon_duplicateIssue() {
        // given
        couponIssueService.issueCoupon(activeCoupon.getId(), testUser);

        // when & then
        assertThatThrownBy(() -> couponIssueService.issueCoupon(activeCoupon.getId(), testUser))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(businessException.getMessage()).contains("이미 발급받은 쿠폰입니다");
                });
    }

    // 사용자의 보유 쿠폰 목록을 정상적으로 조회하는 테스트
    // 다수의 쿠폰 발급 후 해당 사용자 기준으로 2개 쿠폰만 반환되는지 확인
    @Test
    @DisplayName("내 쿠폰 조회 성공")
    void searchMyCoupon_success() {
        // given
        couponIssueService.issueCoupon(activeCoupon.getId(), testUser);

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
        couponIssueService.issueCoupon(coupon2.getId(), testUser);

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
        couponIssueService.issueCoupon(activeCoupon.getId(), otherUser);

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
        couponIssueService.issueCoupon(activeCoupon.getId(), testUser);

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
        CouponIssueResponse issued = couponIssueService.issueCoupon(activeCoupon.getId(), testUser);
        
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
        CouponIssueResponse issued = couponIssueService.issueCoupon(activeCoupon.getId(), testUser);
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
        CouponIssueResponse issued = couponIssueService.issueCoupon(percentCoupon.getId(), testUser);
        Integer cost = 12500; // 10% 할인이면 1250원, 백원 단위 절삭이면 1200원

        // when
        Integer discount = couponIssueService.calculateDiscount(issued.getCouponIssueId(), testUser.getId(), cost);

        // then
        // 12500 * 0.1 = 1250, 백원 단위 절삭하면 1200
        assertThat(discount).isEqualTo(1200);
    }

    // 존재하지 않는 쿠폰 이슈 ID로 할인 금액 계산 시 실패해야 함
    // ErrorCode.NOT_FOUND 예외 발생 검증
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
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                });
    }

    // 다른 사용자가 소유한 쿠폰으로 할인 요청 시 실패 테스트
    // 권한 검증 로직 작동 확인 (ErrorCode.FORBIDDEN)
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

        CouponIssueResponse issued = couponIssueService.issueCoupon(activeCoupon.getId(), otherUser);

        // when & then
        assertThatThrownBy(() -> couponIssueService.calculateDiscount(issued.getCouponIssueId(), testUser.getId(), 10000))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(businessException.getMessage()).contains("쿠폰 소유자와 사용자가 일치하지 않습니다");
                });
    }

    // 만료된 쿠폰으로 할인 금액 계산 요청 시 실패 테스트
    // ErrorCode.FORBIDDEN 및 메시지 “쿠폰 사용일이 만료되었습니다” 확인
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
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(businessException.getMessage()).contains("쿠폰 사용일이 만료되었습니다");
                });
    }

    // 쿠폰 사용 정상 흐름 테스트
    // 사용 후 CouponIssue.isActive=false, deletedAt이 갱신되는지 검증
    @Test
    @DisplayName("쿠폰 사용 성공")
    void useCoupon_success() {
        // given
        CouponIssueResponse issued = couponIssueService.issueCoupon(activeCoupon.getId(), testUser);

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
    // ErrorCode.NOT_FOUND 예외 발생 검증
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
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                });
    }

    // 다른 사용자의 쿠폰을 사용하려 할 때 실패하는 시나리오
    // ErrorCode.FORBIDDEN 예외 및 메시지 검증
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

        CouponIssueResponse issued = couponIssueService.issueCoupon(activeCoupon.getId(), otherUser);

        // when & then
        assertThatThrownBy(() -> couponIssueService.useCoupon(issued.getCouponIssueId(), testUser.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(businessException.getMessage()).contains("쿠폰 소유자와 사용자가 일치하지 않습니다");
                });
    }

    // 이미 사용 처리된 쿠폰을 다시 사용하려 하면 실패해야 하는 테스트
    // ErrorCode.FORBIDDEN 예외 발생 확인
    @Test
    @DisplayName("쿠폰 사용 실패 - 이미 사용된 쿠폰")
    void useCoupon_alreadyUsed() {
        // given
        CouponIssueResponse issued = couponIssueService.issueCoupon(activeCoupon.getId(), testUser);
        couponIssueService.useCoupon(issued.getCouponIssueId(), testUser.getId());

        // when & then
        assertThatThrownBy(() -> couponIssueService.useCoupon(issued.getCouponIssueId(), testUser.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                });
    }

    // 만료된 쿠폰을 사용하려 할 때 실패 테스트
    // ErrorCode.FORBIDDEN 및 메시지 “쿠폰 사용일이 만료되었습니다” 확인
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
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(businessException.getMessage()).contains("쿠폰 사용일이 만료되었습니다");
                });
    }
}

