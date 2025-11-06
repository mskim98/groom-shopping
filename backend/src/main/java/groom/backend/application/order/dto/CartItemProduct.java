package groom.backend.application.order.dto;

import java.util.UUID;

public record CartItemProduct(UUID productId, Integer quantity) {
}