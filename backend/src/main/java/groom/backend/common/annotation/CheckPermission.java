package groom.backend.common.annotation;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 메서드 실행 전 권한을 확인하는 어노테이션.
 *
 * - 이 어노테이션이 적용된 메서드는 권한 검증 로직이 실행됨.
 * - 주로 관리자 권한이 필요한 API에 사용됨.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CheckPermission {

    // 검사할 권한 목록 (여러 개 가능)
    String[] roles() default {"USER"};

    // 검사 모드 : ALL - hasAuthority, ANY - hasAnyAuthority
    Mode mode() default Mode.ANY;

    Page page() default Page.BO;

    enum Mode {
        ALL, // hasAuthority : 모든 권한 필요
        ANY // hasAnyAuthority : 하나 이상의 권한 필요
    }

    enum Page {
        BO,
        FO
    }
}
