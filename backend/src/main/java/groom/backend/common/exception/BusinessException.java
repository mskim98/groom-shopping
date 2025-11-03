package groom.backend.common.exception;

import lombok.Getter;

/**
 * 비즈니스 로직 예외의 기본 클래스.
 *
 * - RuntimeException을 상속하여 트랜잭션 롤백이 자동 적용됨.
 * - 도메인 규칙 위반, 비즈니스 검증 실패 등의 경우에 사용.
 * - ErrorCode를 포함하여 일관된 에러 응답을 생성할 수 있음.
 */
@Getter
public class BusinessException extends RuntimeException {

  // 에러 코드 (HTTP 상태 및 기본 메시지 포함)
  private final ErrorCode errorCode;

  /**
   * 기본 메시지를 사용하는 생성자
   */
  public BusinessException(ErrorCode errorCode) {
    super(errorCode.getMessage());
    this.errorCode = errorCode;
  }

  /**
   * 세부 메시지를 지정하는 생성자
   * 기본 제공 메시지보다 상세한 상황 제공을 위해 사용
   */
  public BusinessException(ErrorCode errorCode, String detailMessage) {
    super(detailMessage);
    this.errorCode = errorCode;
  }
}
