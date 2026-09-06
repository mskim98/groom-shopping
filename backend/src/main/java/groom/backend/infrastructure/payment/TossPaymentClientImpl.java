package groom.backend.infrastructure.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.infrastructure.payment.dto.TossPaymentConfirmRequest;
import groom.backend.infrastructure.payment.dto.TossPaymentResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class TossPaymentClientImpl implements TossPaymentClient {

    private final RestTemplate restTemplate;
    // 취소 전용(응답 한도 30s · 별도 풀). 승인 템플릿(5s)과 갈라 두는 이유는 RestTemplateConfig 참고
    private final RestTemplate cancelRestTemplate;

    // Lombok @RequiredArgsConstructor 는 @Qualifier 를 생성자 파라미터로 복사하지 않아 직접 쓴다
    public TossPaymentClientImpl(RestTemplate restTemplate,
                                 @Qualifier("tossCancelRestTemplate") RestTemplate cancelRestTemplate) {
        this.restTemplate = restTemplate;
        this.cancelRestTemplate = cancelRestTemplate;
    }

    @Value("${payment.toss.secret-key}")
    private String secretKey;

    @Value("${payment.toss.api-url:https://api.tosspayments.com}")
    private String apiUrl;

    // @CircuitBreaker : "toss" 회로로 보호. 실패율이 임계를 넘으면 회로가 OPEN 되어
    //   이후 호출은 실제 API 로 가지 않고 fallbackMethod 로 즉시 빠진다(fast-fail) → Cascading Failure 방지.
    // @Retry : 일시적 실패(timeout·5xx 등)는 짧게 재시도. 회로 OPEN(CallNotPermittedException)은 재시도 제외(yml).
    // 두 어노테이션은 Spring AOP 프록시 기반이라 다른 빈(PaymentApplicationService)에서 호출돼야 적용된다.
    @CircuitBreaker(name = "toss", fallbackMethod = "confirmPaymentFallback")
    @Retry(name = "toss")
    @Override
    public TossPaymentResponse confirmPayment(TossPaymentConfirmRequest request, String idempotencyKey) {
        String url = apiUrl + "/v1/payments/confirm";

        HttpHeaders headers = createHeaders(idempotencyKey);
        HttpEntity<TossPaymentConfirmRequest> entity = new HttpEntity<>(request, headers);

        log.info("[TOSS_API_REQUEST] Confirm payment - PaymentKey: {}, OrderId: {}, Amount: {}, IdempotencyKey: {}",
                request.getPaymentKey(), request.getOrderId(), request.getAmount(), idempotencyKey);

        try {
            ResponseEntity<TossPaymentResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    TossPaymentResponse.class
            );

            log.info("[TOSS_API_SUCCESS] Payment confirmed - PaymentKey: {}",
                    response.getBody().getPaymentKey());

            return response.getBody();

        } catch (Exception e) {
            log.error("[TOSS_API_ERROR] Payment confirmation failed - Error: {}", e.getMessage());
            throw new RuntimeException("Toss Payments API 호출 실패: " + e.getMessage(), e);
        }
    }

    // Resilience4j fallback : 회로가 OPEN 이거나 재시도까지 실패하면 이 메서드가 대신 호출된다.
    // 규칙 - 원본 메서드와 동일한 파라미터 + 마지막에 Throwable 을 받는다.
    private TossPaymentResponse confirmPaymentFallback(
            TossPaymentConfirmRequest request, String idempotencyKey, Throwable t) {
        if (t instanceof CallNotPermittedException) {
            // 회로 OPEN - 외부 API 를 아예 호출하지 않고 사용자에게 빠른 실패 안내
            log.warn("[CB_FALLBACK_OPEN] Circuit OPEN, fast-fail - PaymentKey: {}", request.getPaymentKey());
            throw new BusinessException(ErrorCode.PAYMENT_TEMPORARILY_UNAVAILABLE);
        }
        // timeout·5xx 등 실제 호출 실패 - 결제 PG 오류로 변환 (이후 보상 트랜잭션이 사후 정합성 담당)
        log.error("[CB_FALLBACK_FAILURE] Toss confirm failed - PaymentKey: {}, Error: {}",
                request.getPaymentKey(), t.getMessage());
        throw new BusinessException(ErrorCode.PAYMENT_PG_FAILURE);
    }

    // @CircuitBreaker : 취소는 승인과 별도 회로("toss-cancel")로 보호한다
    // 같은 회로를 쓰면 취소 실패가 승인 회로를 열어 정상 결제까지 막고, 반대로 승인 장애가
    // 보상 취소를 fast-fail 시켜 채무만 쌓인다. 두 방향 모두 나쁘므로 분리한다
    // @CircuitBreaker : 사용자 수동 취소 엔드포인트 전용. 배치는 계수하지 않는 cancelPaymentUncounted 를 쓴다(결정 C)
    @CircuitBreaker(name = "toss-cancel", fallbackMethod = "cancelPaymentFallback")
    @Override
    public TossPaymentResponse cancelPayment(String paymentKey, String cancelReason, String idempotencyKey) {
        return doCancel(paymentKey, cancelReason, idempotencyKey);
    }

    // 배치 전용 - 회로에 계수되지 않는다. 분류 예외(429/403/409/ALREADY_CANCELED)와 실패는 그대로 던져
    // 보상 서비스의 recordResult 가 값으로 받아 처리한다(fallback 불필요)
    @Override
    public TossPaymentResponse cancelPaymentUncounted(String paymentKey, String cancelReason, String idempotencyKey) {
        return doCancel(paymentKey, cancelReason, idempotencyKey);
    }

    // 실제 취소 HTTP 호출·응답 분류. 계수/미계수 두 진입점이 공유한다
    private TossPaymentResponse doCancel(String paymentKey, String cancelReason, String idempotencyKey) {
        String url = apiUrl + "/v1/payments/" + paymentKey + "/cancel";

        HttpHeaders headers = createHeaders(idempotencyKey);
        Map<String, String> body = new HashMap<>();
        body.put("cancelReason", cancelReason);

        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);

        log.info("[TOSS_API_REQUEST] Cancel payment - PaymentKey: {}, Reason: {}, IdempotencyKey: {}",
                paymentKey, cancelReason, idempotencyKey);

        try {
            ResponseEntity<TossPaymentResponse> response = cancelRestTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    TossPaymentResponse.class
            );

            log.info("[TOSS_API_SUCCESS] Payment cancelled - PaymentKey: {}", paymentKey);

            return response.getBody();

        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            String tossErrorCode = extractTossErrorCode(e.getResponseBodyAsString());

            if (status == 429
                    || (status == 403 && "FORBIDDEN_CONSECUTIVE_REQUEST".equals(tossErrorCode))
                    || (status == 409 && "IDEMPOTENT_REQUEST_PROCESSING".equals(tossErrorCode))) {
                log.warn("[TOSS_API_RETRY_LATER] Cancel deferred - PaymentKey: {}, Status: {}, Code: {}",
                        paymentKey, status, tossErrorCode);
                throw new TossRetryLaterException(
                        "Toss 취소 응답이 재시도 대기를 지시함 - status: " + status + ", code: " + tossErrorCode);
            }
            if (status == 400 && "ALREADY_CANCELED_PAYMENT".equals(tossErrorCode)) {
                log.info("[TOSS_API_ALREADY_CANCELED] Cancel already applied - PaymentKey: {}", paymentKey);
                throw new TossAlreadyCanceledException(
                        "Toss 가 이미 취소 처리한 결제 - PaymentKey: " + paymentKey);
            }
            log.error("[TOSS_API_ERROR] Payment cancellation failed - Error: {}", e.getMessage());
            throw new RuntimeException("Toss Payments API 호출 실패: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("[TOSS_API_ERROR] Payment cancellation failed - Error: {}", e.getMessage());
            throw new RuntimeException("Toss Payments API 호출 실패: " + e.getMessage(), e);
        }
    }

    // 토스 오류 본문의 code 필드만 추출. 파싱 실패(빈 본문·JSON 아님)는 null 로 처리해 기존 실패 경로로 떨어뜨린다
    private static String extractTossErrorCode(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            return new ObjectMapper().readTree(responseBody).path("code").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    // 취소 회로 fallback. 보상 서비스의 catch 가 이 예외를 받아 실패로 기록하고 백오프 재시도한다
    private TossPaymentResponse cancelPaymentFallback(
            String paymentKey, String cancelReason, String idempotencyKey, Throwable t) {
        if (t instanceof TossRetryLaterException || t instanceof TossAlreadyCanceledException) {
            // 분류된 예외는 그대로 보상 서비스에 넘긴다 - PAYMENT_PG_FAILURE 로 뭉개면
            // 재시도 대기와 이미 취소된 결제를 구별할 수 없다
            throw (RuntimeException) t;
        }
        if (t instanceof CallNotPermittedException) {
            log.warn("[CB_CANCEL_FALLBACK_OPEN] 취소 회로 OPEN, fast-fail - PaymentKey: {}", paymentKey);
        } else {
            log.error("[CB_CANCEL_FALLBACK_FAILURE] Toss cancel failed - PaymentKey: {}, Error: {}",
                    paymentKey, t.getMessage());
        }
        throw new BusinessException(ErrorCode.PAYMENT_PG_FAILURE);
    }

    private HttpHeaders createHeaders(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Toss Payments Idempotency-Key: 재시도 시 동일 키면 이전 응답을 돌려준다 → 이중 결제 방지.
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            headers.set("Idempotency-Key", idempotencyKey);
        }

        // Basic Auth. 설정 주입 과정에서 BOM 이 섞이는 사례가 있어 제거 후 인코딩한다
        // 시크릿 키는 어떤 형태로도 로그에 남기지 않는다 - Base64 는 원문을 그대로 복원할 수 있다
        String cleanSecretKey = secretKey.replaceFirst("^\\uFEFF", "").trim();
        String encodedAuth = Base64.getEncoder()
                .encodeToString((cleanSecretKey + ":").getBytes(StandardCharsets.UTF_8));
        headers.set("Authorization", "Basic " + encodedAuth);

        return headers;
    }
}
