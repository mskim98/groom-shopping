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
  SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

  // HTTP 상태 코드
  private final HttpStatus status;

  // 클라이언트에게 반환될 메시지
  private final String message;
}

