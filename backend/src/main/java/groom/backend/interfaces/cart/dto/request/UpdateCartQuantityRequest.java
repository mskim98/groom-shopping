package groom.backend.interfaces.cart.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * 장바구니 수량 변경 요청 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "장바구니 수량 변경 요청 DTO")
public class UpdateCartQuantityRequest {
    
    @NotNull(message = "제품 ID는 필수입니다")
    @Schema(description = "제품 ID", example = "550e8400-e29b-41d4-a716-446655440000", required = true)
    private UUID productId;
}

