package groom.backend.infrastructure.payment;

import groom.backend.infrastructure.payment.dto.TossPaymentConfirmRequest;
import groom.backend.infrastructure.payment.dto.TossPaymentResponse;
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

    @Override
    public TossPaymentResponse confirmPayment(TossPaymentConfirmRequest request) {
        String url = apiUrl + "/v1/payments/confirm";

        HttpHeaders headers = createHeaders();
        HttpEntity<TossPaymentConfirmRequest> entity = new HttpEntity<>(request, headers);

        log.info("[TOSS_API_REQUEST] Confirm payment - PaymentKey: {}, OrderId: {}, Amount: {}",
                request.getPaymentKey(), request.getOrderId(), request.getAmount());

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

    @Override
    public TossPaymentResponse cancelPayment(String paymentKey, String cancelReason) {
        String url = apiUrl + "/v1/payments/" + paymentKey + "/cancel";

        HttpHeaders headers = createHeaders();
        Map<String, String> body = new HashMap<>();
        body.put("cancelReason", cancelReason);

        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);

        log.info("[TOSS_API_REQUEST] Cancel payment - PaymentKey: {}, Reason: {}",
                paymentKey, cancelReason);

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

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Basic Auth: Secret Key를 Base64로 인코딩
        String auth = secretKey + ":";
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
        headers.set("Authorization", "Basic " + encodedAuth);

        return headers;
    }
}
