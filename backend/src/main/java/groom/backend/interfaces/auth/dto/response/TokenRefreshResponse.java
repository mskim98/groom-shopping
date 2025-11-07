package groom.backend.interfaces.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Schema(description = "토큰 갱신 응답 DTO")
public class TokenRefreshResponse {
    @Schema(description = "새로 발급된 액세스 토큰", example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbkB0ZXN0LmNvbSIsInJvbGUiOiJST0xFX0FETUlOIiwiaWF0IjoxNzYyMDUyMDc0LCJleHAiOjE3NjIwNTM4NzR9.llRvRtWQIoxH5mNU9GiFU9uCmiQgvU-BNmmVFv4TxmA")
    private String accessToken;
}
