package groom.backend.interfaces.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "토큰 갱신 요청 DTO")
public class TokenRefreshRequest {
    @NotBlank
    @Schema(description = "리프레시 토큰 값", example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbkB0ZXN0LmNvbSIsInJvbGUiOiJST0xFX0FETUlOIiwiaWF0IjoxNzYyMDUyMDQ1LCJleHAiOjE3NjI2NTY4NDV9.lvI7f4ELomdFP_QOfnFmF0E6n-u1VIDGso4OYYPoqnE", requiredMode = Schema.RequiredMode.REQUIRED)
    private String refreshToken;
}
