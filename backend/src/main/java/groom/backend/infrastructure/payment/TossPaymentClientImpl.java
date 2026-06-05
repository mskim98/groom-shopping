package groom.backend.infrastructure.payment;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class TossPaymentClientImpl implements TossPaymentClient {

    private final RestTemplate restTemplate;

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

    @Override
    public TossPaymentResponse cancelPayment(String paymentKey, String cancelReason, String idempotencyKey) {
        String url = apiUrl + "/v1/payments/" + paymentKey + "/cancel";

        HttpHeaders headers = createHeaders(idempotencyKey);
        Map<String, String> body = new HashMap<>();
        body.put("cancelReason", cancelReason);

        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);

        log.info("[TOSS_API_REQUEST] Cancel payment - PaymentKey: {}, Reason: {}, IdempotencyKey: {}",
                paymentKey, cancelReason, idempotencyKey);

        try {
            ResponseEntity<TossPaymentResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    TossPaymentResponse.class
            );

            log.info("[TOSS_API_SUCCESS] Payment cancelled - PaymentKey: {}", paymentKey);

            return response.getBody();

        } catch (Exception e) {
            log.error("[TOSS_API_ERROR] Payment cancellation failed - Error: {}", e.getMessage());
            throw new RuntimeException("Toss Payments API 호출 실패: " + e.getMessage(), e);
        }
    }

    private HttpHeaders createHeaders(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Toss Payments Idempotency-Key: 재시도 시 동일 키면 이전 응답을 돌려준다 → 이중 결제 방지.
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            headers.set("Idempotency-Key", idempotencyKey);
        }

        // Basic Auth: Secret Key를 Base64로 인코딩
        // 원본 시크릿 키 상태 확인
        log.info("[TOSS_AUTH_DEBUG] Original secretKey: length={}, hex={}, starts with BOM={}",
                secretKey.length(),
                byteArrayToHex(secretKey.getBytes(StandardCharsets.UTF_8), 20),
                secretKey.startsWith("\uFEFF"));

        // BOM(Byte Order Mark) 제거 (UTF-8 BOM: \ufeff)
        String cleanSecretKey = secretKey.replaceFirst("^\\uFEFF", "").trim();

        log.info("[TOSS_AUTH_DEBUG] Cleaned secretKey: length={}, hex={}",
                cleanSecretKey.length(),
                byteArrayToHex(cleanSecretKey.getBytes(StandardCharsets.UTF_8), 20));

        String auth = cleanSecretKey + ":";
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));

        log.info("[TOSS_AUTH_DEBUG] Encoded auth: {}", encodedAuth);
        log.info("[TOSS_AUTH_DEBUG] Authorization header: Basic {}", encodedAuth);

        // 정상 인코딩인지 확인
        String expectedEncoded = "dGVzdF9za19Ma0tFeXBOQXJXUWp3SkVFbHlLTjNsbWVheFlHOg==";
        log.info("[TOSS_AUTH_DEBUG] Expected: {}, Match: {}", expectedEncoded, encodedAuth.equals(expectedEncoded));

        headers.set("Authorization", "Basic " + encodedAuth);

        return headers;
    }

    private String byteArrayToHex(byte[] bytes, int limit) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(bytes.length, limit); i++) {
            sb.append(String.format("%02x ", bytes[i]));
        }
        return sb.toString();
    }
}
