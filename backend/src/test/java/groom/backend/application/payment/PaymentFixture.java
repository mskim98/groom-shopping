package groom.backend.application.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.payment.event.PaymentApprovedEvent;
import groom.backend.domain.order.model.Order;
import groom.backend.domain.order.model.OrderItem;
import groom.backend.domain.order.model.enums.OrderStatus;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.enums.PaymentMethod;
import groom.backend.infrastructure.payment.dto.TossPaymentResponse;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.ProductJpaEntity;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 결제 통합 시험이 공유하는 시드·정리 도구. 상품 → 주문(항목 1개) → 결제(PENDING) 한 벌을 만든다
 * 시험마다 새 UUID 를 쓰므로 다른 시험이 남긴 행과 겹치지 않고, 정리는 만든 행만 지운다
 */
final class PaymentFixture {

    static final int PRICE = 1000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SpringDataProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final SpringDataPaymentRepository paymentRepository;
    private final JdbcTemplate jdbc;

    PaymentFixture(SpringDataProductRepository productRepository, OrderRepository orderRepository,
                   SpringDataPaymentRepository paymentRepository, JdbcTemplate jdbc) {
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.jdbc = jdbc;
    }

    /** 재고 stock 개인 상품 1개를 DB 에 만든다. version 을 비워 새 행(persist)으로 저장되게 한다 */
    UUID product(int stock) {
        return productRepository.save(ProductJpaEntity.builder()
                .id(UUID.randomUUID()).name("fixture-product").price(PRICE).stock(stock)
                .isActive(true).category("GENERAL").status("AVAILABLE").build()).getId();
    }

    /** 플래시 세일 상품(Redis 선점 + 원장) 1개를 만든다. stock_mode 는 DB 기본값 REDIS 다 */
    UUID flashProduct(int stock) {
        return productRepository.save(ProductJpaEntity.builder()
                .id(UUID.randomUUID()).name("fixture-flash-product").price(PRICE).stock(stock)
                .isActive(true).category("GENERAL").status("AVAILABLE").flashSale(true).build()).getId();
    }

    /** productId 상품 quantity 개를 담은 주문과 PENDING 결제를 만들어 결제를 돌려준다 */
    Payment payment(UUID productId, int quantity) {
        Order order = Order.builder().userId(1L).couponId(null).status(OrderStatus.PENDING).build();
        order.addOrderItem(OrderItem.builder()
                .productId(productId).productName("fixture-product").price(PRICE).quantity(quantity).build());
        order.calculateAmounts();
        order = orderRepository.save(order);
        return paymentRepository.save(Payment.builder()
                .order(order).userId(1L).amount(PRICE * quantity)
                .orderName("fixture-order").customerName("tester").method(PaymentMethod.CARD).build());
    }

    /** 상품 두 개를 한 주문(항목 2개)에 담은 PENDING 결제를 만든다 */
    Payment payment(UUID firstProductId, int firstQuantity, UUID secondProductId, int secondQuantity) {
        Order order = Order.builder().userId(1L).couponId(null).status(OrderStatus.PENDING).build();
        order.addOrderItem(OrderItem.builder().productId(firstProductId).productName("fixture-product")
                .price(PRICE).quantity(firstQuantity).build());
        order.addOrderItem(OrderItem.builder().productId(secondProductId).productName("fixture-product")
                .price(PRICE).quantity(secondQuantity).build());
        order.calculateAmounts();
        order = orderRepository.save(order);
        return paymentRepository.save(Payment.builder()
                .order(order).userId(1L).amount(PRICE * (firstQuantity + secondQuantity))
                .orderName("fixture-order").customerName("tester").method(PaymentMethod.CARD).build());
    }

    void deleteProduct(UUID productId) {
        productRepository.deleteById(productId);
    }

    /** Toss 승인 응답(최소 필드)을 JSON 으로 만들어 역직렬화한다. 클래스에 setter 가 없어 필드로 채워진다 */
    static TossPaymentResponse tossResponse(String paymentKey, int amount) {
        try {
            return MAPPER.readValue("{\"paymentKey\":\"" + paymentKey + "\",\"totalAmount\":" + amount
                    + ",\"status\":\"DONE\",\"requestedAt\":\"2026-11-09T10:00:00+09:00\"}", TossPaymentResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 컨슈머가 받는 PaymentApproved 이벤트를 만든다. 선점 항목은 상품 하나 quantity 개다 */
    static PaymentApprovedEvent approvedEvent(Payment payment, UUID productId, int quantity, String paymentKey) {
        int amount = PRICE * quantity;
        return new PaymentApprovedEvent(payment.getId(), payment.getOrderId(), amount,
                tossResponse(paymentKey, amount), List.of(new PaymentApprovedEvent.ReservedItem(productId, quantity)));
    }

    /** 이벤트를 Kafka 에서 받은 모양(key = 결제 id, outbox-id 헤더)의 레코드로 만든다 */
    static ConsumerRecord<String, String> record(ObjectMapper mapper, PaymentApprovedEvent event)
            throws JsonProcessingException {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment-events", 0, 0L,
                event.paymentId().toString(), mapper.writeValueAsString(event));
        record.headers().add("outbox-id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    /** payment 한 벌과 매단 보상·아웃박스 행, 주문, 상품을 순서대로 지운다 */
    void cleanup(Payment payment, UUID productId) {
        jdbc.update("DELETE FROM payment_compensation WHERE payment_id = ?", payment.getId());
        jdbc.update("DELETE FROM payment_outbox WHERE aggregate_id = ?", payment.getId());
        jdbc.update("DELETE FROM stock_ledger WHERE payment_id = ? OR product_id = ?", payment.getId(), productId);
        paymentRepository.deleteById(payment.getId());
        orderRepository.deleteById(payment.getOrderId());
        productRepository.deleteById(productId);
    }

    /** 상품은 남기고 결제 한 벌(보상 · 아웃박스 · 원장 · 결제 · 주문)만 지운다 - 한 상품에 결제가 둘인 시험용 */
    void cleanupPaymentOnly(Payment payment) {
        jdbc.update("DELETE FROM payment_compensation WHERE payment_id = ?", payment.getId());
        jdbc.update("DELETE FROM payment_outbox WHERE aggregate_id = ?", payment.getId());
        jdbc.update("DELETE FROM stock_ledger WHERE payment_id = ?", payment.getId());
        paymentRepository.deleteById(payment.getId());
        orderRepository.deleteById(payment.getOrderId());
    }

    /** 원장 행(종류 · 수량 · 적용 여부)을 id 순으로 읽는다 */
    List<java.util.Map<String, Object>> ledgerRows(UUID paymentId) {
        return jdbc.queryForList(
                "SELECT product_id, qty, kind, applied FROM stock_ledger WHERE payment_id = ? ORDER BY id", paymentId);
    }

    /** 상품의 아직 적용되지 않은 원장 수량 합 */
    int unappliedSumOf(UUID productId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(qty), 0) FROM stock_ledger WHERE product_id = ? AND applied = false",
                Integer.class, productId);
    }

    String stockModeOf(UUID productId) {
        return jdbc.queryForObject("SELECT stock_mode FROM product WHERE id = ?", String.class, productId);
    }

    /** 결제의 원장 수량 합(SALE + REVERSAL, 적용 여부 무관) */
    int ledgerSumOf(UUID paymentId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(qty), 0) FROM stock_ledger WHERE payment_id = ?",
                Integer.class, paymentId);
    }

    /** 승인 직후 상태(READY, paymentKey 부착)로 맞춘다 - 컨슈머가 이벤트를 받는 시작 상태다 */
    void markReady(UUID paymentId, String paymentKey) {
        jdbc.update("UPDATE payment SET status = 'READY', payment_key = ? WHERE id = ?", paymentKey, paymentId);
    }

    String orderStatusOf(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    String failureCodeOf(UUID paymentId) {
        return jdbc.queryForObject("SELECT failure_code FROM payment WHERE id = ?", String.class, paymentId);
    }

    int compensationCountOf(UUID paymentId) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_compensation WHERE payment_id = ?",
                Integer.class, paymentId);
    }

    int stockOf(UUID productId) {
        return jdbc.queryForObject("SELECT stock FROM product WHERE id = ?", Integer.class, productId);
    }

    String statusOf(UUID paymentId) {
        return jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, paymentId);
    }
}
