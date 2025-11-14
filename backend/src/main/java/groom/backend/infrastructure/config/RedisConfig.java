package groom.backend.infrastructure.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import groom.backend.domain.auth.entity.RefreshToken;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import groom.backend.interfaces.coupon.dto.response.CouponIssueResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableCaching
public class RedisConfig {

    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    // --- 쿠폰 도메인 캐시 이름 상수 ---
    public static final String COUPON_ITEM_CACHE_NAME = "coupon-item-cache";
    public static final String COUPON_LIST_CACHE_NAME = "user-coupons-list-cache";

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        return new LettuceConnectionFactory(host, port);
    }

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
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
    public RedisTemplate<String, String> cartRedisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }


    @Bean(name = "tokenRedisTemplate")
    public RedisTemplate<String, RefreshToken> tokenRedisTemplate(RedisConnectionFactory factory) {

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(objectMapper);

        RedisTemplate<String, RefreshToken> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);
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

        // [수정] 주입받은 objectMapper 대신, JavaTimeModule만 등록된 새 ObjectMapper 사용
        // (기존 objectMapper() 빈의 설정에 따라 의도치 않은 'activateDefaultTyping'이 적용될 수 있음)
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule()); // JavaTimeModule 등록
        jacksonSerializer.setObjectMapper(om);

        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(jacksonSerializer);
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashValueSerializer(jacksonSerializer);

        redisTemplate.afterPropertiesSet(); // 설정 적용
        return redisTemplate;
    }

    // --- [3. 추가] CacheManager 빈 ---
    /**
     * @Cacheable, @CacheEvict 등 Spring Cache 추상화가 사용할 CacheManager
     */
    @Bean
    public CacheManager couponCacheManager(RedisConnectionFactory redisConnectionFactory) {

        // 4. CacheManager 전용 ObjectMapper 설정 (Type 정보 포함)
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator
                .builder()
                .allowIfBaseType(Object.class) // 모든 타입 허용
                .build();

        ObjectMapper cacheObjectMapper = new ObjectMapper();
        cacheObjectMapper.registerModule(new JavaTimeModule());
        // [중요] JSON에 @class 필드를 추가하여, List<> 같은 제네릭 타입도 역직렬화 가능하게 함
        cacheObjectMapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL);

        // 5. CacheManager의 기본 설정
        RedisCacheConfiguration defaultCacheConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                // [중요] List<> 처리를 위해 GenericJackson2JsonRedisSerializer 사용
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer(cacheObjectMapper)))
                .entryTtl(Duration.ofMinutes(10)); // 기본 TTL 10분

        // 6. 캐시별로 다른 TTL 설정 (선택 사항)
        Map<String, RedisCacheConfiguration> perCacheConfig = new HashMap<>();
        perCacheConfig.put(COUPON_ITEM_CACHE_NAME, defaultCacheConfig.entryTtl(Duration.ofHours(1))); // 단건 캐시: 1시간
        perCacheConfig.put(COUPON_LIST_CACHE_NAME, defaultCacheConfig.entryTtl(Duration.ofMinutes(5))); // 목록 캐시: 5분

        return RedisCacheManager.RedisCacheManagerBuilder
                .fromConnectionFactory(redisConnectionFactory)
                .cacheDefaults(defaultCacheConfig) // 기본 설정
                .withInitialCacheConfigurations(perCacheConfig) // 캐시별 맞춤 설정
                .build();
    }
}
