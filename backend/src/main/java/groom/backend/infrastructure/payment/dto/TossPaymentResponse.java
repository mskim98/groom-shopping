package groom.backend.infrastructure.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TossPaymentResponse {

    // 기본 정보
    private String mId;
    private String version;
    private String paymentKey;
    private String orderId;
    private String orderName;
    private String method;
    private String status;
    private String type;
    private String currency;

    // 금액 정보
    private Integer totalAmount;
    private Integer balanceAmount;
    private Integer suppliedAmount;
    private Integer vat;
    private Integer taxFreeAmount;
    private Integer taxExemptionAmount;

    // 거래 정보
    private String lastTransactionKey;
    private String transactionKey;
    private String requestedAt;
    private String approvedAt;

    // 결제 상태 정보
    private Boolean useEscrow;
    private Boolean cultureExpense;
    private Boolean isPartialCancelable;

    // 결제 수단별 상세정보 (JSON 문자열로 처리)
    private Object card;
    private Object virtualAccount;
    private Object transfer;
    private Object mobilePhone;
    private Object giftCertificate;
    private Object easyPay;

    // 추가 정보
    private Object receipt;
    private Object checkout;
    private Object failure;
    private Object cashReceipt;
    private Object cashReceipts;
    private Object discount;
    private Object cancels;
}
