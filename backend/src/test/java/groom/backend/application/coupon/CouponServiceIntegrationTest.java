package groom.backend.application.coupon;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.model.enums.CouponType;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.interfaces.coupon.dto.request.CouponCreateRequest;
import groom.backend.interfaces.coupon.dto.request.CouponSearchCondition;
import groom.backend.interfaces.coupon.dto.request.CouponUpdateRequest;
import groom.backend.interfaces.coupon.dto.response.CouponResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("CouponService 통합 테스트")
class CouponServiceIntegrationTest {

    @Autowired
    private CouponService couponService;

    @Autowired
    private CouponRepository couponRepository;

    private CouponCreateRequest createRequest;

    @BeforeEach
    void setUp() {
        // 테스트 데이터 초기화
        couponRepository.deleteAll();
        
        createRequest = CouponCreateRequest.builder()
                .name("테스트 쿠폰")
                .description("테스트용 쿠폰입니다")
                .quantity(100L)
                .amount(1000)
                .type(CouponType.DISCOUNT)
                .expireDate(LocalDate.now().plusDays(30))
                .build();
    }

    // 쿠폰 생성 기능의 정상 동작을 검증하는 테스트
    // CouponService.createCoupon() 호출 시 CouponResponse가 반환되는지 확인
    // 생성된 쿠폰의 필드 값(name, description, quantity 등)이 요청값과 일치하는지 검증
    // 실제 DB에 저장된 쿠폰 데이터가 정상적으로 반영되었는지도 확인
    @Test
    @DisplayName("쿠폰 생성 성공")
    void createCoupon_success() {
        // when
        CouponResponse response = couponService.createCoupon(createRequest);

        // then
        assertThat(response).isNotNull();
        assertThat(response.getId()).isNotNull();
        assertThat(response.getName()).isEqualTo("테스트 쿠폰");
        assertThat(response.getDescription()).isEqualTo("테스트용 쿠폰입니다");
        assertThat(response.getQuantity()).isEqualTo(100L);
        assertThat(response.getAmount()).isEqualTo(1000);
        assertThat(response.getType()).isEqualTo(CouponType.DISCOUNT);
        assertThat(response.getIsActive()).isTrue();
        assertThat(response.getExpireDate()).isEqualTo(LocalDate.now().plusDays(30));

        // DB에 저장되었는지 확인
        Coupon savedCoupon = couponRepository.findById(response.getId()).orElseThrow();
        assertThat(savedCoupon.getName()).isEqualTo("테스트 쿠폰");
    }

    // 생성된 쿠폰을 ID 기반으로 정상 조회할 수 있는지 테스트
    // CouponService.findCoupon()이 정확한 쿠폰 정보를 반환하는지 검증
    @Test
    @DisplayName("쿠폰 단일 조회 성공")
    void findCoupon_success() {
        // given
        CouponResponse created = couponService.createCoupon(createRequest);
        Long couponId = created.getId();

        // when
        CouponResponse response = couponService.findCoupon(couponId);

        // then
        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(couponId);
        assertThat(response.getName()).isEqualTo("테스트 쿠폰");
    }

    // 존재하지 않는 쿠폰 ID로 조회 시 예외가 발생하는지 테스트
    // BusinessException이 ErrorCode.NOT_FOUND와 함께 발생해야 함
    @Test
    @DisplayName("쿠폰 단일 조회 실패 - 존재하지 않는 쿠폰")
    void findCoupon_notFound() {
        // given
        Long nonExistentId = 999L;

        // when & then
        assertThatThrownBy(() -> couponService.findCoupon(nonExistentId))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                });
    }

    // 쿠폰 이름 조건으로 검색하는 기능 테스트
    // “할인”이라는 이름이 포함된 쿠폰만 검색되어야 하며, 총 검색 결과가 1개인지 검증
    @Test
    @DisplayName("쿠폰 조건부 검색 - 이름으로 검색")
    void searchCoupon_byName() {
        // given
        couponService.createCoupon(createRequest);
        
        CouponCreateRequest request2 = CouponCreateRequest.builder()
                .name("할인 쿠폰")
                .description("할인용")
                .quantity(50L)
                .amount(500)
                .type(CouponType.DISCOUNT)
                .expireDate(LocalDate.now().plusDays(30))
                .build();
        couponService.createCoupon(request2);

        CouponSearchCondition condition = CouponSearchCondition.builder()
                .name("할인")
                .build();
        Pageable pageable = PageRequest.of(0, 10);

        // when
        Page<CouponResponse> response = couponService.searchCoupon(condition, pageable);

        // then
        assertThat(response).isNotNull();
        assertThat(response.getTotalElements()).isEqualTo(1);
        assertThat(response.getContent().getFirst().getName()).contains("할인");
    }

    // 쿠폰 타입 조건으로 검색하는 기능 테스트
    // CouponType.PERCENT 타입 쿠폰만 반환되는지 확인
    // 결과 페이지에서 타입이 PERCENT로 일치하는지 검증
    @Test
    @DisplayName("쿠폰 조건부 검색 - 타입으로 검색")
    void searchCoupon_byType() {
        // given
        couponService.createCoupon(createRequest);
        
        CouponCreateRequest percentRequest = CouponCreateRequest.builder()
                .name("퍼센트 쿠폰")
                .description("퍼센트용")
                .quantity(50L)
                .amount(10)
                .type(CouponType.PERCENT)
                .expireDate(LocalDate.now().plusDays(30))
                .build();
        couponService.createCoupon(percentRequest);

        CouponSearchCondition condition = CouponSearchCondition.builder()
                .type(CouponType.PERCENT)
                .build();
        Pageable pageable = PageRequest.of(0, 10);

        // when
        Page<CouponResponse> response = couponService.searchCoupon(condition, pageable);

        // then
        assertThat(response).isNotNull();
        assertThat(response.getTotalElements()).isEqualTo(1);
        assertThat(response.getContent().getFirst().getType()).isEqualTo(CouponType.PERCENT);
    }

    // 쿠폰의 활성화 상태(isActive)로 필터링하는 검색 기능 테스트
    // 비활성화된 쿠폰만 존재할 때, 활성화 쿠폰 조건으로 검색하면 결과가 0개여야 함
    @Test
    @DisplayName("쿠폰 조건부 검색 - 활성화 여부로 검색, 실패")
    void searchCoupon_byIsActive() {
        // given
        CouponResponse created = couponService.createCoupon(createRequest);
        
        CouponUpdateRequest updateRequest = CouponUpdateRequest.builder()
                .isActive(false)
                .build();
        couponService.updateCoupon(created.getId(), updateRequest);

        CouponSearchCondition condition = CouponSearchCondition.builder()
                .isActive(true)
                .build();
        Pageable pageable = PageRequest.of(0, 10);

        // when
        Page<CouponResponse> response = couponService.searchCoupon(condition, pageable);

        // then
        assertThat(response).isNotNull();
        assertThat(response.getTotalElements()).isEqualTo(0);
    }

    // 페이징 기능 테스트
    // 총 15개의 쿠폰 생성 후, PageRequest(0,10), PageRequest(1,10)으로 나누어 검색
    // 첫 페이지는 10개, 두 번째 페이지는 5개가 반환되어야 함
    @Test
    @DisplayName("쿠폰 조건부 검색 - 페이징")
    void searchCoupon_pagination() {
        // given
        for (int i = 0; i < 15; i++) {
            CouponCreateRequest request = CouponCreateRequest.builder()
                    .name("쿠폰 " + i)
                    .description("설명 " + i)
                    .quantity(10L)
                    .amount(100)
                    .type(CouponType.DISCOUNT)
                    .expireDate(LocalDate.now().plusDays(30))
                    .build();
            couponService.createCoupon(request);
        }

        CouponSearchCondition condition = CouponSearchCondition.builder().build();
        Pageable pageable = PageRequest.of(0, 10);

        // when
        Page<CouponResponse> firstPage = couponService.searchCoupon(condition, pageable);
        Page<CouponResponse> secondPage = couponService.searchCoupon(condition, PageRequest.of(1, 10));

        // then
        assertThat(firstPage.getTotalElements()).isEqualTo(15);
        assertThat(firstPage.getContent().size()).isEqualTo(10);
        assertThat(secondPage.getContent().size()).isEqualTo(5);
    }

    // 쿠폰 수정 기능의 정상 동작을 검증하는 테스트
    // 기존 쿠폰의 name, description, quantity, expireDate 값을 변경 후 반영 여부 확인
    // 수정된 CouponResponse 및 실제 DB 값이 일치하는지 검증
    @Test
    @DisplayName("쿠폰 수정 성공")
    void updateCoupon_success() {
        // given
        CouponResponse created = couponService.createCoupon(createRequest);
        Long couponId = created.getId();

        CouponUpdateRequest updateRequest = CouponUpdateRequest.builder()
                .name("수정된 쿠폰")
                .description("수정된 설명")
                .quantity(200L)
                .expireDate(LocalDate.now().plusDays(60))
                .build();

        // when
        CouponResponse response = couponService.updateCoupon(couponId, updateRequest);

        // then
        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(couponId);
        assertThat(response.getName()).isEqualTo("수정된 쿠폰");
        assertThat(response.getDescription()).isEqualTo("수정된 설명");
        assertThat(response.getQuantity()).isEqualTo(200L);
        assertThat(response.getExpireDate()).isEqualTo(LocalDate.now().plusDays(60));

        // DB에서 확인
        Coupon updatedCoupon = couponRepository.findById(couponId).orElseThrow();
        assertThat(updatedCoupon.getName()).isEqualTo("수정된 쿠폰");
        assertThat(updatedCoupon.getQuantity()).isEqualTo(200L);
    }

    // 존재하지 않는 쿠폰 ID로 수정 요청 시 예외가 발생하는지 테스트
    // BusinessException과 ErrorCode.NOT_FOUND 검증
    @Test
    @DisplayName("쿠폰 수정 실패 - 존재하지 않는 쿠폰")
    void updateCoupon_notFound() {
        // given
        Long nonExistentId = 999L;
        CouponUpdateRequest updateRequest = CouponUpdateRequest.builder()
                .name("수정된 쿠폰")
                .build();

        // when & then
        assertThatThrownBy(() -> couponService.updateCoupon(nonExistentId, updateRequest))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                });
    }

    // 부분 수정(Partial Update) 기능 검증 테스트
    // 일부 필드만 수정 요청 시, 나머지 null 필드는 기존 값이 유지되는지 확인
    @Test
    @DisplayName("쿠폰 수정 - 부분 수정 (null 값은 무시)")
    void updateCoupon_partialUpdate() {
        // given
        CouponResponse created = couponService.createCoupon(createRequest);
        Long couponId = created.getId();

        CouponUpdateRequest updateRequest = CouponUpdateRequest.builder()
                .name("이름만 수정")
                .build();

        // when
        CouponResponse response = couponService.updateCoupon(couponId, updateRequest);

        // then
        assertThat(response.getName()).isEqualTo("이름만 수정");
        assertThat(response.getDescription()).isEqualTo("테스트용 쿠폰입니다"); // 기존 값 유지
        assertThat(response.getQuantity()).isEqualTo(100L); // 기존 값 유지
    }

    // 쿠폰 삭제 기능 테스트
    // 쿠폰 삭제 후 결과(Boolean)가 true이고, 실제 DB에서도 존재하지 않아야 함
    @Test
    @DisplayName("쿠폰 삭제 성공")
    void deleteCoupon_success() {
        // given
        CouponResponse created = couponService.createCoupon(createRequest);
        Long couponId = created.getId();

        // when
        Boolean result = couponService.deleteCoupon(couponId);

        // then
        assertThat(result).isTrue();
        assertThat(couponRepository.existsById(couponId)).isFalse();
    }

    // 존재하지 않는 쿠폰 ID로 삭제 요청 시 false가 반환되는지 테스트
    // 예외 대신 단순 실패(Boolean false) 반환 검증
    @Test
    @DisplayName("쿠폰 삭제 실패 - 존재하지 않는 쿠폰")
    void deleteCoupon_notFound() {
        // given
        Long nonExistentId = 999L;

        // when
        Boolean result = couponService.deleteCoupon(nonExistentId);

        // then
        assertThat(result).isFalse();
    }

    // 여러 쿠폰 중 하나를 삭제했을 때, 다른 쿠폰이 영향을 받지 않는지 검증
    // 삭제된 쿠폰은 DB에서 존재하지 않아야 하고, 남은 쿠폰은 여전히 조회 가능해야 함
    @Test
    @DisplayName("쿠폰 삭제 후 다른 쿠폰 조회 가능")
    void deleteCoupon_otherCouponsStillExist() {
        // given
        CouponResponse coupon1 = couponService.createCoupon(createRequest);
        
        CouponCreateRequest request2 = CouponCreateRequest.builder()
                .name("다른 쿠폰")
                .description("다른 설명")
                .quantity(50L)
                .amount(500)
                .type(CouponType.DISCOUNT)
                .expireDate(LocalDate.now().plusDays(30))
                .build();
        CouponResponse coupon2 = couponService.createCoupon(request2);

        // when
        couponService.deleteCoupon(coupon1.getId());

        // then
        assertThat(couponRepository.existsById(coupon1.getId())).isFalse();
        assertThat(couponRepository.existsById(coupon2.getId())).isTrue();
        
        CouponResponse found = couponService.findCoupon(coupon2.getId());
        assertThat(found).isNotNull();
        assertThat(found.getName()).isEqualTo("다른 쿠폰");
    }
}

