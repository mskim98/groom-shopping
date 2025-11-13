package groom.backend.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 전역적으로 사용되는 에러 코드 정의 Enum.
 * 각 에러는 HTTP 상태 코드(HttpStatus)와 사용자 메시지를 함께 가짐.
 *
 * Controller → Service → Repository 계층에서 발생한 예외를
 * 표준화된 형태로 응답하기 위해 사용됨.
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    // ==================== 공통 에러 코드 ====================
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    CONFLICT(HttpStatus.CONFLICT, "이미 존재하는 리소스입니다."),
    SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

    // ===================== Auth 에러 코드 ====================
    /** 유효하지 않은 토큰 */
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    /** 유효하지 않은 Refresh 토큰 */
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 리프레시 토큰입니다."),
    /** Refresh 토큰 없음 */
    NO_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "리프레시 토큰이 존재하지 않습니다."),
    /** Refresh 토큰 다름 */
    MISMATCH_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "리프레시 토큰이 일치하지 않습니다."),
    /** 만료된 토큰 */
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "만료된 토큰입니다."),
    /** 사용자 없음 */
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    /** 권한 없음 */
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    /** 로그인 실패 */
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "아이디 혹은 비밀번호가 잘못 입력되었습니다."),
    /** 계정 잠김 */
    ACCOUNT_LOCKED(HttpStatus.LOCKED, "계정이 잠겨있습니다."),
    /** 비활성화된 계정 */
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "비활성화된 계정입니다."),
    /** 이메일 중복 */
    EMAIL_DUPLICATION(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),

    // ===================== Raffle 에러 코드 ====================

    // 요청/검증 관련
    REQUEST_IS_NULL                     (HttpStatus.BAD_REQUEST, "요청 값이 null입니다."),
    INVALID_RAFFLE_STATUS               (HttpStatus.BAD_REQUEST, "유효하지 않은 추첨 상태입니다."),
    INVALID_RAFFLE_DATES                (HttpStatus.BAD_REQUEST, "응모일과 추첨일이 올바르지 않습니다."),
    RAFFLE_REQUIRED_DATES               (HttpStatus.BAD_REQUEST, "응모 시작일, 응모 종료일, 추첨일은 반드시 입력해야 합니다."),

    // 리소스 없음(Not Found)
    PRODUCT_NOT_FOUND                   (HttpStatus.NOT_FOUND, "해당 상품을 찾을 수 없습니다."),
    RAFFLE_NOT_FOUND                    (HttpStatus.NOT_FOUND, "해당 추첨을 찾을 수 없습니다."),
    RAFFLE_NOT_FOUND_FOR_PRODUCT        (HttpStatus.NOT_FOUND, "해당 상품으로 등록된 추첨이 존재하지 않습니다."),

    // 중복/충돌
    DUPLICATE_RAFFLE_PRODUCT            (HttpStatus.CONFLICT, "이미 추첨에 사용된 상품입니다."),
    RAFFLE_ALREADY_EXISTS               (HttpStatus.CONFLICT, "이미 존재하는 추첨입니다."),

    // 상품 상태 / 재고
    RAFFLE_PRODUCT_NOT_FOUND          (HttpStatus.NOT_FOUND, "추첨 상품을 찾을 수 없습니다."),
    WINNER_PRODUCT_NOT_FOUND          (HttpStatus.NOT_FOUND, "증정 상품을 찾을 수 없습니다."),
    RAFFLE_PRODUCT_NOT_ACTIVE           (HttpStatus.BAD_REQUEST, "추첨 상품이 비활성화 상태입니다."),
    WINNER_PRODUCT_NOT_ACTIVE           (HttpStatus.BAD_REQUEST, "증정 상품이 비활성화 상태입니다."),
    INSUFFICIENT_RAFFLE_PRODUCT_STOCK          (HttpStatus.BAD_REQUEST, "추첨 상품의 재고가 부족합니다."),
    INSUFFICIENT_WINNER_PRODUCT_STOCK          (HttpStatus.BAD_REQUEST, "증정 상품의 재고가 부족합니다."),
    INVALID_RAFFLE_PRODUCT_TYPE          (HttpStatus.BAD_REQUEST, "추첨 상품용이 아닙니다."),
    INVALID_WINNER_PRODUCT_TYPE          (HttpStatus.BAD_REQUEST, "증정 상품용이 아닙니다."),

    // 응모(입력) 관련
    RAFFLE_ENTRY_LIMIT_EXCEEDED         (HttpStatus.BAD_REQUEST, "응모 한도를 초과하였습니다."),
    RAFFLE_ENTRY_NOT_STARTED            (HttpStatus.BAD_REQUEST, "응모 기간이 아직 시작되지 않았습니다."),
    RAFFLE_ENTRY_ENDED                  (HttpStatus.BAD_REQUEST, "응모 기간이 종료되었습니다."),

    // 날짜 관계 검증
    RAFFLE_DRAW_DATE_AFTER_END_DATE     (HttpStatus.BAD_REQUEST, "추첨일은 응모 종료일 이후여야 합니다."),
    RAFFLE_END_DATE_AFTER_START_DATE    (HttpStatus.BAD_REQUEST, "응모 종료일은 응모 시작일 이후여야 합니다."),

    // 추첨 진행 관련
    RAFFLE_NO_PARTICIPANTS              (HttpStatus.BAD_REQUEST, "응모자가 없어 당첨자 추첨을 진행할 수 없습니다."),
    RAFFLE_ALL_WINNERS_DRAWN            (HttpStatus.BAD_REQUEST, "이미 모든 당첨자가 추첨되었습니다."),
    RAFFLE_DRAW_FAILED                  (HttpStatus.INTERNAL_SERVER_ERROR, "당첨자 추첨에 실패했습니다. 다시 시도해주세요."),

    // 수정/삭제 제한
    RAFFLE_CANNOT_BE_DELETED            (HttpStatus.BAD_REQUEST, "현재 상태에서는 추첨을 삭제할 수 없습니다."),
    RAFFLE_NOT_EDITABLE                 (HttpStatus.BAD_REQUEST, "현재 상태에서는 추첨을 수정할 수 없습니다."),

    // 추첨 시작 전 검증(별도)
    RAFFLE_DRAW_NOT_STARTED             (HttpStatus.BAD_REQUEST, "아직 추첨일이 되지 않았습니다."),

    // ===================== Cart 에러 코드 ====================
    /** 장바구니가 존재하지 않음 */
    CART_NOT_FOUND                      (HttpStatus.NOT_FOUND, "장바구니가 존재하지 않습니다."),
    /** 장바구니에 제품이 존재하지 않음 */
    CART_ITEM_NOT_FOUND                 (HttpStatus.NOT_FOUND, "해당 제품이 장바구니에 존재하지 않습니다."),
    /** 제품 재고 부족 */
    INSUFFICIENT_STOCK                  (HttpStatus.BAD_REQUEST, "재고가 부족합니다."),
    /** 판매 중지된 제품 */
    PRODUCT_NOT_ACTIVE                  (HttpStatus.BAD_REQUEST, "판매 중지된 제품입니다."),
    /** 장바구니 수량 초과 */
    CART_QUANTITY_EXCEEDED              (HttpStatus.BAD_REQUEST, "장바구니의 제품보다 수량이 큽니다."),
    /** 최소 수량 미달 */
    CART_QUANTITY_MINIMUM               (HttpStatus.BAD_REQUEST, "수량은 1개 이상이어야 합니다."),
    /** 제거 수량 유효성 검증 실패 */
    INVALID_REMOVE_QUANTITY             (HttpStatus.BAD_REQUEST, "제거할 수량은 1 이상이어야 합니다.");

    // ===================== Coupon 에러 코드 ====================
    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "쿠폰을 찾을 수 없습니다."),
    COUPON_DATE_INVALID(HttpStatus.BAD_REQUEST, "처리할 수 없는 날짜입니다."),

    COUPON_USER_MATCH_FAILED(HttpStatus.FORBIDDEN, "쿠폰 소유자와 사용자가 일치하지 않습니다."),
    COUPON_EXPIRED(HttpStatus.FORBIDDEN, "쿠폰 사용일이 만료되었습니다."),

    COUPON_INVALID_POLICY(HttpStatus.BAD_REQUEST, "쿠폰 정책에 맞지 않은 사용방식입니다."),
    COUPON_OUT_OF_STOCK(HttpStatus.CONFLICT, "발급 수량이 소진되었습니다."),
    COUPON_ALREADY_ISSUED(HttpStatus.CONFLICT, "이미 발급받은 쿠폰입니다.");


    // HTTP 상태 코드
    private final HttpStatus status;

    // 클라이언트에게 반환될 메시지
    private final String message;
}
