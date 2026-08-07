package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import groom.backend.application.payment.event.PaymentCompensationDlqEvent;
import groom.backend.domain.payment.model.PaymentCompensation;
import groom.backend.domain.payment.model.enums.CompensationStatus;
import groom.backend.domain.payment.repository.PaymentCompensationRepository;
import groom.backend.infrastructure.kafka.PaymentCompensationDlqProducer;
import groom.backend.infrastructure.payment.TossPaymentClient;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentCompensationServiceTest {

    @Mock
    private PaymentCompensationRepository compensationRepository;
    @Mock
    private TossPaymentClient tossPaymentClient;
    @Mock
    private PaymentCompensationDlqProducer dlqProducer;
    @InjectMocks
    private PaymentCompensationService service;

    private PaymentCompensation newCompensation(int maxRetry) {
        return PaymentCompensation.builder()
                .paymentId(UUID.randomUUID())
                .paymentKey("pk-1")
                .amount(1000)
                .reason("DB 후처리 실패")
                .maxRetryCount(maxRetry)
                .build();
    }

    @Test
    void executeCompensation_재시도_한도_초과로_GIVEN_UP되면_DLQ로_발행한다() {
        PaymentCompensation compensation = newCompensation(3);
        given(tossPaymentClient.cancelPayment(anyString(), anyString(), anyString()))
                .willThrow(new RuntimeException("toss down"));
        given(compensationRepository.save(any())).willReturn(compensation);

        // maxRetry=3 → 3번째 실패에서 GIVEN_UP 전이
        for (int i = 0; i < 3; i++) {
            service.executeCompensation(compensation);
        }

        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.GIVEN_UP);
        verify(dlqProducer, times(1)).publish(any(PaymentCompensationDlqEvent.class));
    }

    @Test
    void executeCompensation_성공하면_DLQ로_발행하지_않는다() {
        PaymentCompensation compensation = newCompensation(3);
        given(tossPaymentClient.cancelPayment(anyString(), anyString(), anyString())).willReturn(null);
        given(compensationRepository.save(any())).willReturn(compensation);

        service.executeCompensation(compensation);

        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.SUCCEEDED);
        verify(dlqProducer, never()).publish(any());
    }
}
