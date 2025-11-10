package groom.backend.infrastructure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
        info = @Info(
                title = "Got-cha! API Docs",
                description = """
                        이 API는 JWT 기반 인증을 사용합니다.
                        Swagger 상단의 [Authorize] 버튼을 눌러 발급받은 JWT 토큰을 입력하면,
                        모든 인증이 필요한 API 호출 시 Authorization 헤더에 자동으로 토큰이 추가됩니다.
        
                        - 토큰 형식: Bearer {JWT}
                        - 인증이 필요한 API: `/api/**`
                        - 인증 없이 접근 가능한 경로: `/auth/**`, `/swagger-ui/**`
        
                        ⚠️ Swagger 설정은 문서용이며, 실제 인증 검증은 Spring Security에서 수행됩니다.
                        
                        nginx를 통해 리버스 프록시 기능을 제공합니다. 실제로 api 요청 시에는 모두 `api/` 를 prefix로 붙여 요청해야 합니다.
                        """,
                version = "v1"
        )
)
@Configuration
public class SwaggerConfig {

  private static final String BEARER_TOKEN_PREFIX = "Bearer";

  @Bean
  public OpenAPI openAPI() {
    String securityJwtName = "JWT";
    SecurityRequirement securityRequirement = new SecurityRequirement().addList(securityJwtName);
    Components components = new Components()
            .addSecuritySchemes(securityJwtName, new SecurityScheme()
                    .name(securityJwtName)
                    .type(SecurityScheme.Type.HTTP)
                    .scheme(BEARER_TOKEN_PREFIX)
                    .bearerFormat(securityJwtName));

    return new OpenAPI()
            .addSecurityItem(securityRequirement)
            .components(components);
  }

}
