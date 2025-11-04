package groom.backend.domain.product.model.vo;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Price {

    @Column(name = "price", nullable = false)
    private Integer amount;

    public Price(Integer amount) {
        validate(amount);
        this.amount = amount;
    }

    private void validate(Integer amount) {
        if (amount == null) {
            throw new IllegalArgumentException("가격은 필수입니다");
        }
        if (amount < 0) {
            throw new IllegalArgumentException("가격은 0 이상이어야 합니다");
        }
    }

    public Price increase(Integer amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("증가할 금액은 양수여야 합니다.");
        }
        return new Price(this.amount + amount);
    }

    public Price decrease(Integer amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("감소할 금액은 양수여야 합니다.");
        }
        if (this.amount < amount) {
            throw new IllegalArgumentException("감소시킬 금액보다 원래 금액이 적습니다."); // 도메인 의미 명확
        }
        return new Price(this.amount - amount);
    }

    public Integer getValue() {
        return this.amount;
    }
}
