package groom.backend.infrastructure.config;

import groom.backend.infrastructure.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final UserDetailsService userDetailsService;

    // 측정 전용 필터. 조건이 안 맞으면 빈이 없으므로 Optional 로 받는다
    private final java.util.Optional<groom.backend.infrastructure.security.LoadTestAuthBypassFilter> loadTestAuthBypassFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // CSRF 비활성화
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))   // 세션 비활성화
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/v1/auth/signup", "/v1/auth/login", "/v1/auth/refresh").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html")
                        .permitAll()  // Swagger 경로 허용
                        .requestMatchers(HttpMethod.GET, "/v1/product/**").permitAll()  // 상품 조회 허용
                        .requestMatchers(HttpMethod.GET, "/v1/raffles","/v1/raffles/*").permitAll()  // 래플 조회 허용
                        .requestMatchers("/actuator", "/actuator/**").permitAll()  // Actuator 모니터링 엔드포인트 허용
                        .anyRequest().authenticated()  // 모든 요청 인증 필요
                )
                .formLogin(login -> login.disable()) // 로그인 폼 비활성화
                .httpBasic(basic -> basic.disable()) // 기본 인증 비활성화
                .authenticationProvider(authenticationProvider()) // 커스텀 AuthenticationProvider 등록
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class); // JWT 필터를 스프링 시큐리티의 UsernamePasswordAuthenticationFilter 이전에 삽입하여 토큰 기반 인증 처리.

        // 측정 전용 우회 필터는 빈이 있을 때만 끼운다 (dev + loadtest.auth-bypass.enabled)
        // JWT 필터보다 앞에 둬야 토큰 없이 들어온 요청이 인증 없음으로 먼저 잘리지 않는다
        loadTestAuthBypassFilter.ifPresent(filter ->
                http.addFilterBefore(filter, JwtAuthenticationFilter.class));

        return http.build();
    }

    // 측정 전용 우회 필터도 같은 이중 등록 함정에 걸린다
    // 서블릿 쪽이 먼저 돌면서 요청을 '처리됨' 으로 표시하면, 시큐리티 체인 안의 같은 필터가 건너뛴다
    // 그 사이 SecurityContextHolderFilter 가 컨텍스트를 비우므로 인증이 통째로 사라져 401 이 된다 (실제로 겪었다)
    @Bean
    public FilterRegistrationBean<groom.backend.infrastructure.security.LoadTestAuthBypassFilter>
            loadTestAuthBypassFilterRegistration(
            java.util.Optional<groom.backend.infrastructure.security.LoadTestAuthBypassFilter> filter) {
        FilterRegistrationBean<groom.backend.infrastructure.security.LoadTestAuthBypassFilter> registration =
                new FilterRegistrationBean<>();
        filter.ifPresent(registration::setFilter);
        registration.setEnabled(false);
        return registration;
    }

    // JwtAuthenticationFilter 는 @Component 라 서블릿 컨테이너에 자동 등록되어 시큐리티 체인과 '이중 등록'된다.
    // (OncePerRequestFilter 라 요청당 1회만 실행되지만) 서블릿 자동 등록을 꺼서 시큐리티 체인에서만 동작하도록 한다.
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}