package groom.backend.common.aop;

import groom.backend.common.annotation.CheckPermission;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.infrastructure.security.JwtTokenProvider;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Aspect
@Component
@Slf4j
public class PermissionAspect {

    private final JwtTokenProvider jwtTokenProvider;

    public PermissionAspect(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Before("@annotation(groom.backend.common.annotation.CheckPermission)")
    public void checkPermission(JoinPoint joinPoint) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        // 어노테이션에서 권한 정보 가져오기
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        CheckPermission permission = method.getAnnotation(CheckPermission.class);

        String[] requiredRoles = permission.roles();
        CheckPermission.Mode mode = permission.mode();
        CheckPermission.Page page = permission.page();

        Set<String> userRoleNames = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        // 요구 권한을 "ROLE_" 접두사로 정규화
        Set<String> requiredNormalized = Arrays.stream(requiredRoles)
                .map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r)
                .collect(Collectors.toSet());

        boolean hasPermission;
        if (mode == CheckPermission.Mode.ALL) {
            // 모든 권한이 있어야 함
            hasPermission = userRoleNames.containsAll(requiredNormalized);
        } else {
            // 하나라도 일치하면 허용
            hasPermission = requiredNormalized.stream().anyMatch(userRoleNames::contains);
        }

        if (!hasPermission) {

            String message = "접근 권한이 없습니다.";

            log.debug("필요한 권한: {}, 현재 권한: {}", requiredNormalized, userRoleNames);
            if (page == CheckPermission.Page.BO) {
                message = "필요한 권한: " + requiredNormalized + ", 현재 권한: " + userRoleNames;
            }

            throw new BusinessException(
                    ErrorCode.ACCESS_DENIED,
                    message
            );
        }
    }
}
