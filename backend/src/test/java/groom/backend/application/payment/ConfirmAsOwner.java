package groom.backend.application.payment;

import groom.backend.domain.payment.model.Payment;

/**
 * 결제 승인 시험 도구 - 승인 호출자를 결제 주인으로 넘긴다.
 * 승인 서비스는 호출자 id 가 결제 주인이 아니면(null 포함) 거절하므로, 시험이 주인 id 를 대신 채운다
 */
public final class ConfirmAsOwner {

    private ConfirmAsOwner() {
    }

    public static Payment confirm(PaymentApplicationService service, Payment payment, String paymentKey, Integer amount) {
        return service.confirmPayment(payment.getUserId(), paymentKey, payment.getOrderId(), amount);
    }
}
