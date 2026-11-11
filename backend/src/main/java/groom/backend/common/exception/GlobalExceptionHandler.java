package groom.backend.common.exception;

import groom.backend.common.exception.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 전역 예외 처리 클래스.
 *
 * - @RestControllerAdvice: 모든 Controller에서 발생한 예외를 감지하여 처리함.
 * - 예외 유형별로 다른 ErrorResponse를 생성하여 일관된 형식으로 응답.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  /**
   * 비즈니스 로직 예외 처리
   * ex) 쿠폰이 존재하지 않음, 중복 발급 등
   */
  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
    ErrorCode errorCode = e.getErrorCode();
    log.warn("[BusinessException] {} - {}", errorCode.name(), e.getMessage());
    return ResponseEntity.status(errorCode.getStatus())
            .body(ErrorResponse.from(errorCode, e.getMessage()));
  }


  /**
   * DTO 유효성 검증 실패
   * ex) @Valid, @NotNull 등을 위반한 경우
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
    String detail = e.getBindingResult().getFieldErrors().stream()
            .map(err -> err.getField() + ": " + err.getDefaultMessage())
            .findFirst()
            .orElse("잘못된 입력값입니다.");
    log.warn("[ValidationException] {}", detail);
    return ResponseEntity.badRequest()
            .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "INVALID_PARAMETER", detail));
  }

  /**
   * 없는 경로 요청 - 클라이언트 오류가 500 으로 집계되어 5xx 경보를 울리지 않도록 404 로 답한다
   * (노출하지 않은 /actuator/env 도 여기서 404 가 된다)
   */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
    log.warn("[NoResourceFound] {}", e.getResourcePath());
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.from(ErrorCode.NOT_FOUND));
  }

  /**
   * 그 외 예상치 못한 모든 예외 처리 (서버 내부 오류)
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleException(Exception e) {
    log.error("[UnhandledException]", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse.from(ErrorCode.SERVER_ERROR));
  }
}

