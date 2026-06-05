package groom.backend.domain.order.model;

import groom.backend.domain.order.model.enums.OrderStatus;
import groom.backend.domain.payment.model.Payment;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 주문 애그리거트 루트.
 *
 * <p>장바구니에서 넘어온 상품들을 하나의 "주문"으로 묶는 도메인 객체이다.
 * 주문 한 건은 여러 개의 {@link OrderItem}(주문 상품)을 가지며,
 * 결제 한 건({@link Payment})과 1:1로 연결된다.</p>
 *
 * <p>금액 구조: {@code totalAmount = subTotal - discountAmount} (음수면 0으로 보정).
 * 상태는 {@link OrderStatus}를 따라 PENDING → ... 으로 변한다.</p>
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor
public class Order {

    // 주문 식별자 (UUID, JPA가 자동 생성)
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    // 주문한 사용자 ID
    @Column(nullable = false)
    private Long userId;

    // 할인 적용 전 주문 상품 금액 합계 (모든 OrderItem subTotal의 합)
    @Column(nullable = false)
    private Integer subTotal;

    // 쿠폰 등으로 깎인 할인 금액 (없으면 0)
    @Column
    private Integer discountAmount;

    // 사용자가 실제로 결제할 최종 금액 (subTotal - discountAmount)
    @Column(nullable = false)
    private Integer totalAmount;

    // 주문 진행 상태 (PENDING, ... ) - 문자열로 DB에 저장
    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    // 주문 생성 시각 (최초 저장 시 자동 입력, 이후 변경 안 됨)
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 마지막 수정 시각 (저장될 때마다 자동 갱신)
    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    // 적용된 쿠폰 ID (쿠폰을 쓰지 않은 주문이면 null)
    @Column
    private Long couponId;

    // 이 주문에 연결된 결제 (1:1). 주문 저장/삭제 시 결제도 함께 처리됨(CascadeType.ALL)
    @OneToOne(mappedBy = "order", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private Payment payment;

    // 이 주문에 담긴 주문 상품 목록 (1:N). 주문에서 빠진 항목은 DB에서도 삭제(orphanRemoval)
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> orderItems = new ArrayList<>();

    @Builder
    public Order(Long userId, Long couponId, OrderStatus status) {
        this.userId = userId;
        this.couponId = couponId;
        this.status = status != null ? status : OrderStatus.PENDING;
        this.subTotal = 0;
        this.discountAmount = 0;
        this.totalAmount = 0;
    }

    /** 이 주문에 결제 객체를 연결한다. */
    public void assignPayment(Payment payment) {
        this.payment = payment;
    }

    /** 할인 금액을 설정한다. null이 들어오면 0으로 처리한다. */
    public void setDiscountAmount(Integer discountAmount) {
        this.discountAmount = discountAmount != null ? discountAmount : 0;
    }

    /**
     * 주문 상품을 추가하고 양방향 연관관계(OrderItem → Order)를 함께 설정한다.
     * 한쪽만 설정하면 데이터가 어긋날 수 있으므로 반드시 이 메서드로 추가한다.
     */
    public void addOrderItem(OrderItem orderItem) {
        orderItems.add(orderItem);
        orderItem.setOrder(this);
    }

    /** 주문 상태를 변경한다. */
    public void changeStatus(OrderStatus newStatus) {
        this.status = newStatus;
    }

    /**
     * 주문 금액을 다시 계산한다.
     *
     * <p>1) subTotal = 모든 주문 상품 소계의 합,
     * 2) totalAmount = subTotal - 할인 금액,
     * 3) totalAmount가 음수면 0으로 보정한다.</p>
     */
    public void calculateAmounts() {
        this.subTotal = orderItems.stream()
                .map(OrderItem::getSubtotal)           // Integer 값 반환
                .filter(Objects::nonNull)              // null 방지
                .reduce(0, Integer::sum);              // Integer 합계 계산

        int discount = this.discountAmount != null ? this.discountAmount : 0;

        this.totalAmount = this.subTotal - discount;

        if (this.totalAmount < 0) {
            this.totalAmount = 0;
        }
    }


    // 엔티티 동일성은 식별자(id)로만 판단한다 (JPA 엔티티의 일반적인 equals/hashCode 규약)
    // 주의: 아래 instanceof 검사가 OrderItem으로 되어 있어 항상 false를 반환하는 버그가 있음 (Order로 수정 필요)
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OrderItem)) {
            return false;
        }
        Order order = (Order) o;
        return Objects.equals(id, order.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
