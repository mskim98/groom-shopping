package groom.backend.domain.order.model;

import groom.backend.domain.order.model.enums.OrderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
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

@Entity
@Table(name = "Order")
@Getter
@NoArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "userId", nullable = false)
    private Long userId;

    @Column(name = "subTotal", nullable = false)
    private Integer subTotal;

    @Column(name = "discountAmount")
    private Integer discountAmount;

    @Column(name = "totalAmount", nullable = false)
    private Integer totalAmount;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @CreationTimestamp
    @Column(name = "createdAt", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updatedAt", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "couponId")
    private Long couponId;

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

    public void setDiscountAmount(Integer discountAmount) {
        this.discountAmount = discountAmount != null ? discountAmount : 0;
    }

    public void addOrderItem(OrderItem orderItem) {
        orderItems.add(orderItem);
        orderItem.setOrder(this);
    }

    public void changeStatus(OrderStatus newStatus) {
        this.status = newStatus;
    }

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
