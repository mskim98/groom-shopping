package groom.backend.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import groom.backend.interfaces.coupon.dto.response.CouponIssueResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        return new LettuceConnectionFactory(host, port);
    }

    @Bean
    public RedisTemplate<String, String> redisTemplate() {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory());
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        return template;
    }

    /**
     * 장바구니용 RedisTemplate
     * Hash 구조로 사용자별 장바구니 항목 저장
     * Key: cart:{userId}
     * Hash Field: productId (UUID)
     * Hash Value: quantity (JSON)
     */
    @Bean(name = "cartRedisTemplate")
    public RedisTemplate<String, String> cartRedisTemplate() {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory());
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public RedisTemplate<String, CouponIssueResponse> couponCacheTemplate(
            RedisConnectionFactory redisConnectionFactory,
            ObjectMapper objectMapper) { // 3. (선택적) ObjectMapper 주입

        RedisTemplate<String, CouponIssueResponse> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(redisConnectionFactory);

        // 2. DTO용 Jackson Serializer 생성
        Jackson2JsonRedisSerializer<CouponIssueResponse> jacksonSerializer =
                new Jackson2JsonRedisSerializer<>(CouponIssueResponse.class);

        // 3. (권장) ObjectMapper 설정 - 특히 LocalDateTime 직렬화를 위해
        //    (기존에 @Bean으로 등록된 ObjectMapper가 있다면 이 코드는 필요 없을 수 있음)
        ObjectMapper om = objectMapper.copy(); // 주입받은 ObjectMapper 복사
        om.registerModule(new JavaTimeModule()); // JavaTimeModule 등록
        // om.activateDefaultTyping(...) // @class 필드 추가 (필요시)
        jacksonSerializer.setObjectMapper(om);


        // Key Serializer는 String으로 설정 (coupon-item-cache::123)
        redisTemplate.setKeySerializer(new StringRedisSerializer());

        // Value Serializer는 JSON(Jackson)으로 설정
        redisTemplate.setValueSerializer(jacksonSerializer);

        // Hash Key/Value Serializer도 설정 (필요시)
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashValueSerializer(jacksonSerializer);

        redisTemplate.afterPropertiesSet(); // 설정 적용
        return redisTemplate;
    }

    // 4. (참고) 만약 @Bean ObjectMapper가 없다면 새로 생성
     @Bean
     public ObjectMapper objectMapper() {
         ObjectMapper mapper = new ObjectMapper();
         mapper.registerModule(new JavaTimeModule());
         return mapper;
     }
}
