package groom.backend.domain.product.model.vo;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Description {

    @Column(name = "description", length = 1000, nullable = true)
    private String value;

    public Description(String value) {
        validate(value);
        this.value = value;
    }

    private void validate(String value) {
        if (value != null && value.length() > 1000) {
            throw new IllegalArgumentException("상품 설명은 1000자 이하여야 합니다");
        }
    }

    public boolean isEmpty() {
        return value == null || value.isBlank();
    }
}