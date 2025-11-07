package groom.backend.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Groom Shopping API")
                        .description("Groom Shopping 백엔드 API 문서")
                        .version("v1.0.0"))
                .servers(List.of(
                        new Server().url("/api").description("API Server")
                ));
    }
}

