package groom.backend.interfaces.payment;

import groom.backend.application.payment.PaymentApplicationService;
import groom.backend.common.annotation.CheckPermission;
import groom.backend.domain.auth.entity.User;
import groom.backend.domain.payment.model.Payment;
import groom.backend.interfaces.payment.dto.request.CancelPaymentRequest;
import groom.backend.interfaces.payment.dto.request.ConfirmPaymentRequest;
import groom.backend.interfaces.payment.dto.response.PaymentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/v1/payment")
@RequiredArgsConstructor
@Tag(name = "Payment", description = "결제 관련 API")
@SecurityRequirement(name = "JWT")
public class PaymentController {

    private final PaymentApplicationService paymentApplicationService;

    /**
     * 결제 승인 - Toss Payments API 호출 후 상태 변경
     */
    @Operation(
            summary = "결제 승인",
            description = "Toss Payments API를 호출하여 결제를 승인합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "결제 승인 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PaymentResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다.")
    })
    @PostMapping("/confirm")
    public ResponseEntity<PaymentResponse> confirmPayment(
            @Parameter(hidden = true) @AuthenticationPrincipal(expression = "user") User user,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "결제 승인 요청",
                    required = true,
                    content = @Content(schema = @Schema(implementation = ConfirmPaymentRequest.class))
            )
            @RequestBody ConfirmPaymentRequest request) {

        log.info("[API_REQUEST] Confirm payment - UserId: {}, OrderId: {}, PaymentKey: {}",
                user.getId(), request.getOrderId(), request.getPaymentKey());

        Payment payment = paymentApplicationService.confirmPayment(
                request.getPaymentKey(),
                request.getOrderId(),
                request.getAmount()
        );

        PaymentResponse response = PaymentResponse.from(payment);

        log.info("[API_RESPONSE] Payment confirmed - PaymentId: {}, Status: {}",
                response.getId(), response.getStatus());

        return ResponseEntity.ok(response);
    }

    /**
     * 결제 취소
     */
    @Operation(
            summary = "결제 취소",
            description = "결제를 취소합니다. 취소 사유를 입력할 수 있습니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "결제 취소 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PaymentResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다.")
    })
    @CheckPermission(roles = {"ADMIN"}, mode = CheckPermission.Mode.ANY, page = CheckPermission.Page.BO)
    @PostMapping("/cancel")
    public ResponseEntity<PaymentResponse> cancelPayment(
            @Parameter(hidden = true) @AuthenticationPrincipal(expression = "user") User user,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "결제 취소 요청",
                    required = true,
                    content = @Content(schema = @Schema(implementation = CancelPaymentRequest.class))
            )
            @RequestBody CancelPaymentRequest request) {

        log.info("[API_REQUEST] Cancel payment - UserId: {}, PaymentId: {}, Reason: {}",
                user.getId(), request.getPaymentId(), request.getCancelReason());

        Payment payment = paymentApplicationService.cancelPayment(
                request.getPaymentId(),
                request.getCancelReason()
        );

        PaymentResponse response = PaymentResponse.from(payment);

        log.info("[API_RESPONSE] Payment cancelled - PaymentId: {}, Status: {}",
                response.getId(), response.getStatus());

        return ResponseEntity.ok(response);
    }

    /**
     * 결제 조회
     */
    @Operation(
            summary = "결제 조회",
            description = "결제 ID로 결제 정보를 조회합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "결제 조회 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PaymentResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다."),
            @ApiResponse(responseCode = "404", description = "결제를 찾을 수 없음")
    })
    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getPayment(
            @Parameter(hidden = true) @AuthenticationPrincipal(expression = "user") User user,
            @Parameter(description = "결제 ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable UUID paymentId) {

        log.info("[API_REQUEST] Get payment - UserId: {}, PaymentId: {}",
                user.getId(), paymentId);

        Payment payment = paymentApplicationService.getPayment(paymentId);
        PaymentResponse response = PaymentResponse.from(payment);

        return ResponseEntity.ok(response);
    }

    /**
     * 주문의 결제 조회
     */
    @Operation(
            summary = "주문별 결제 조회",
            description = "주문 ID로 해당 주문의 결제 정보를 조회합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "결제 조회 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PaymentResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다."),
            @ApiResponse(responseCode = "404", description = "결제를 찾을 수 없음")
    })
    @GetMapping("/order/{orderId}")
    public ResponseEntity<PaymentResponse> getPaymentByOrderId(
            @Parameter(hidden = true) @AuthenticationPrincipal(expression = "user") User user,
            @Parameter(description = "주문 ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable UUID orderId) {

        log.info("[API_REQUEST] Get payment by order - UserId: {}, OrderId: {}",
                user.getId(), orderId);

        Payment payment = paymentApplicationService.getPaymentByOrderId(orderId);
        PaymentResponse response = PaymentResponse.from(payment);

        return ResponseEntity.ok(response);
    }

    /**
     * 사용자의 결제 목록 조회
     */
    @Operation(
            summary = "내 결제 목록 조회",
            description = "현재 로그인한 사용자의 모든 결제 내역을 조회합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "결제 목록 조회 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PaymentResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다.")
    })
    @GetMapping("/my")
    public ResponseEntity<List<PaymentResponse>> getMyPayments(
            @Parameter(hidden = true) @AuthenticationPrincipal(expression = "user") User user) {

        log.info("[API_REQUEST] Get my payments - UserId: {}", user.getId());

        List<Payment> payments = paymentApplicationService.getPaymentsByUserId(user.getId());
        List<PaymentResponse> responses = payments.stream()
                .map(PaymentResponse::from)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }
}
