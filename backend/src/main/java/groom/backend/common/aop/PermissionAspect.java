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
import org.springframework.core.annotation.AnnotationUtils;
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

    @Before("@within(groom.backend.common.annotation.CheckPermission) || @annotation(groom.backend.common.annotation.CheckPermission)")
    public void checkPermission(JoinPoint joinPoint) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        // 어노테이션에서 권한 정보 가져오기
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        // 실제 구현 메서드에서 어노테이션을 먼저 찾고, 없으면 클래스에서 찾음
        CheckPermission permission = null;
        try {
            // AOP의 JoinPoint에서 실제 타겟(프록시가 감싼 실제 빈) 객체를 가져온다.
            // 주로 CGLIB 프록시나 프록시 대상 객체의 런타임 클래스에서 구현 메서드를 찾아야 할 때 사용.
            Object target = joinPoint.getTarget();
            if (target != null) {
                // MethodSignature에서 얻은 'method'는 인터페이스에 선언된 메서드일 수 있음.
                // 따라서 실제 구현 클래스에서 동일한 시그니처의 메서드를 찾아야
                // 해당 구현 메서드에 직접 붙은 어노테이션을 찾을 수 있다.
                Method implMethod = target.getClass().getMethod(method.getName(), method.getParameterTypes());

                // 구현 메서드에 어노테이션이 붙어있는지 우선 확인.
                // (메서드 레벨 어노테이션이 클래스 레벨보다 우선 적용되어야 할 때 필요)
                permission = AnnotationUtils.findAnnotation(implMethod, CheckPermission.class);

                if (permission == null) {
                    // 구현 메서드에 어노테이션이 없으면 클래스(타입) 레벨 어노테이션 확인.
                    // 타입에 붙은 어노테이션은 해당 클래스의 모든 메서드에 기본 적용된다.
                    permission = AnnotationUtils.findAnnotation(target.getClass(), CheckPermission.class);
                }
            }
        } catch (NoSuchMethodException ignored) {
            // target.getClass().getMethod(...) 호출 시 예외가 발생하면 다음과 같은 경우일 수 있다:
            // - joinPoint에서 얻은 'method'가 인터페이스의 추상 메서드이고 구현 클래스에서 시그니처가 다르게 보이는 경우
            // - 프록시/바이트코드 생성 방식의 차이로 런타임 리플렉션이 직접 매칭하지 못하는 경우
            // 이런 상황에서는 인터페이스(또는 선언한 클래스) 메서드나 그 선언부의 클래스에서 어노테이션을 찾아본다.
            // AnnotationUtils는 메서드/타입에서 메타어노테이션 및 상속된 어노테이션까지 찾아준다.

            // 인터페이스나 선언부에 붙은 어노테이션을 확인
            permission = AnnotationUtils.findAnnotation(method, CheckPermission.class);
            if (permission == null) {
                // 마지막으로 메서드를 선언한 클래스(또는 인터페이스 선언부)에 붙은 타입 레벨 어노테이션 확인
                permission = AnnotationUtils.findAnnotation(method.getDeclaringClass(), CheckPermission.class);
            }
        }

        // 최종적으로 어노테이션이 발견되지 않으면 권한 검사 대상이 아니므로 조기 반환.
        // (어노테이션이 없는데 권한 검사를 수행하면 잘못된 접근 차단 로직이 동작할 수 있으므로 스킵)
        if (permission == null) {
            return; // 어노테이션이 실제로 없으면 권한 검사 스킵
        }

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
