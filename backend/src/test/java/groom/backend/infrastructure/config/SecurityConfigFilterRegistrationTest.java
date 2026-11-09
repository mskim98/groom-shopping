package groom.backend.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import groom.backend.infrastructure.security.JwtAuthenticationFilter;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * 측정 전용 우회 필터가 꺼진 기본 구성에서 서블릿 컨테이너가 기동한다
 *
 * <p>필터 없는 {@code FilterRegistrationBean} 이 빈으로 남으면 톰캣이 등록을 시작하는 순간
 * {@code 'filter' must not be null} 로 기동이 실패한다. 단위 테스트는 목 서블릿 환경이라 이 경로를 타지 않아
 * 기준 시점에도 모든 테스트가 통과했고, 실제 기동만 실패했다
 */
@DisplayName("SecurityConfig 필터 등록")
class SecurityConfigFilterRegistrationTest {

    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    static class SecurityBase {
    }

    private WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withUserConfiguration(SecurityBase.class, SecurityConfig.class)
                .withBean(JwtAuthenticationFilter.class, () -> mock(JwtAuthenticationFilter.class))
                .withBean(UserDetailsService.class, () -> mock(UserDetailsService.class));
    }

    @Test
    @DisplayName("우회 필터 설정이 없으면 필터 없는 FilterRegistrationBean 이 만들어지지 않는다")
    void 우회_필터_설정이_없으면_필터_없는_등록_빈이_없다() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            Map<String, FilterRegistrationBean> registrations = context.getBeansOfType(FilterRegistrationBean.class);
            // 톰캣은 기동 때 각 등록 빈의 onStartup 을 부른다 - 필터가 비어 있으면 거기서 예외가 난다
            for (FilterRegistrationBean<?> registration : registrations.values()) {
                assertThat(registration.getFilter()).as("등록 빈의 필터").isNotNull();
                registration.onStartup(new MockServletContext());
            }
        });
    }
}
