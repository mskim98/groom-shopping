package groom.backend.interfaces.coupon;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import groom.backend.application.coupon.CouponAsyncIssueService;
import groom.backend.application.coupon.CouponIssueService;
import groom.backend.application.coupon.strategy.CouponIssueResult;
import groom.backend.application.coupon.strategy.CouponIssueStrategySelector;
import groom.backend.common.exception.GlobalExceptionHandler;
import groom.backend.domain.auth.entity.User;
import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import groom.backend.domain.coupon.service.CouponCommonService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 비동기 발급 엔드포인트의 응답 코드 (컨트롤러만, 서비스는 가짜)
 *
 * <p>접수가 스트림에 실렸으면 202 와 requestId, Redis 장애로 DB 경로가 그 자리에서 발급했으면 200 과 발급 결과다.
 * 클라이언트는 이 코드로 "상태를 폴링해야 하는가" 를 가른다 - DB 경로의 200 에 requestId 가 없어도 이미 확정이다
 */
@DisplayName("쿠폰 비동기 발급 응답 코드")
class CouponIssueAsyncResponseCodeTest {

    private final CouponAsyncIssueService asyncService = mock(CouponAsyncIssueService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CouponController controller = new CouponController(mock(CouponCommonService.class),
                mock(CouponIssueService.class), asyncService, mock(CouponIssueStrategySelector.class));
        User user = new User(100L, "u@test.com", "pw", "테스터", Role.ROLE_USER, Grade.BRONZE,
                LocalDateTime.now(), LocalDateTime.now());
        // @AuthenticationPrincipal 은 보안 필터가 채운다. 컨트롤러만 시험하므로 같은 자리를 고정된 사용자로 채운다
        HandlerMethodArgumentResolver principal = new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                          NativeWebRequest request, WebDataBinderFactory binderFactory) {
                return user;
            }
        };
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(principal)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("스트림에 접수했으면 202 와 requestId 와 WAITING 을 돌려준다")
    void issueCouponAsync_접수하면_202와_requestId다() throws Exception {
        given(asyncService.enqueue(anyLong(), anyLong())).willReturn(CouponIssueResult.accepted("req-1"));

        mockMvc.perform(post("/v1/coupon/issue-async/1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requestId").value("req-1"))
                .andExpect(jsonPath("$.status").value("WAITING"));
    }

    @Test
    @DisplayName("DB 경로가 그 자리에서 발급했으면 200 과 발급 번호와 SUCCESS 를 돌려준다")
    void issueCouponAsync_db경로가_발급하면_200과_결과다() throws Exception {
        given(asyncService.enqueue(anyLong(), anyLong())).willReturn(CouponIssueResult.success(55L));

        mockMvc.perform(post("/v1/coupon/issue-async/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.couponIssueId").value("55"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("품절이면 409 다")
    void issueCouponAsync_품절이면_409다() throws Exception {
        given(asyncService.enqueue(anyLong(), anyLong())).willReturn(CouponIssueResult.soldOut());

        mockMvc.perform(post("/v1/coupon/issue-async/1")).andExpect(status().isConflict());
    }
}
