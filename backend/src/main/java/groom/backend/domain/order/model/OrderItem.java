package groom.backend.domain.order.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문에 담긴 개별 상품 한 줄(주문 상품).
 *
 * <p>핵심 포인트는 "스냅샷"이다. 상품(Product)을 참조로 들고 있지 않고,
 * 주문 시점의 상품명/가격을 값으로 복사해 둔다.
 * 이렇게 해야 나중에 상품 가격이 바뀌어도 과거 주문 내역이 그대로 보존된다.</p>
 */
@Entity
@Table
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    // 주문 상품 식별자 (DB가 자동 증가시키는 숫자 PK)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 이 항목이 속한 주문 (N:1). 필요할 때만 조회(LAZY)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(nullable = false)
    private Order order;

    // 어떤 상품인지 가리키는 상품 ID (참조가 아닌 ID 값만 보관)
    @Column(nullable = false)
    private UUID productId;

    // 주문 시점의 상품명 스냅샷 (이후 상품명이 바뀌어도 유지)
    @Column(name = "name", nullable = false, length = 200)
    private String productName;

    // 주문 시점의 단가 스냅샷
    @Column(nullable = false, precision = 15, scale = 2)
    private Integer price;

    // 주문 수량
    @Column(nullable = false)
    private Integer quantity;

    // 이 항목의 소계 (price * quantity), 생성 시 한 번 계산해 저장
    @Column(nullable = false, precision = 15, scale = 2)
    private Integer subTotal;

    @Builder
    public OrderItem(UUID productId, String productName, Integer price, Integer quantity) {
        this.productId = productId;
        this.productName = productName;
        this.price = price;
        this.quantity = quantity;
        this.subTotal = price * quantity; // 소계 = 단가 × 수량
    }

    /** 양방향 연관관계 설정용. 외부에서 직접 호출하지 말고 {@link Order#addOrderItem} 을 통해 설정한다. */
    protected void setOrder(Order order) {
        this.order = order;
    }

    /** 이 주문 상품의 소계(단가 × 수량)를 반환한다. */
    public Integer getSubtotal() {
        return this.subTotal;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OrderItem)) {
            return false;
        }
        OrderItem orderItem = (OrderItem) o;
        return Objects.equals(id, orderItem.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}