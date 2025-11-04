package groom.backend.common.exception.dto;

import groom.backend.common.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 예외 발생 시 클라이언트에게 반환되는 표준 JSON 응답 DTO.
 *
 * 예시:
 * {
 *   "status": 404,
 *   "code": "NOT_FOUND",
 *   "message": "요청한 리소스를 찾을 수 없습니다."
 * }
 */
@Getter
@AllArgsConstructor(staticName = "of")
public class ErrorResponse {

  // HTTP 상태 코드 값
  private final int status;

  // 에러 코드 Enum 이름 (e.g. NOT_FOUND, FORBIDDEN)
  private final String code;

  // 클라이언트용 상세 메시지
  private final String message;

  /**
   * ErrorCode 기반으로 ErrorResponse를 생성하는 유틸 메서드
   */
  public static ErrorResponse from(ErrorCode errorCode) {
    return ErrorResponse.of(
            errorCode.getStatus().value(),
            errorCode.name(),
            errorCode.getMessage()
    );
  }

  /**
   * ErrorCode + 사용자 정의 메시지를 함께 사용하는 메서드
   */
  public static ErrorResponse from(ErrorCode errorCode, String detailMessage) {
    return ErrorResponse.of(
            errorCode.getStatus().value(),
            errorCode.name(),
            detailMessage
    );
  }
}
