package groom.backend.infrastructure.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import groom.backend.domain.auth.entity.RefreshToken;
import groom.backend.domain.raffle.entity.RaffleDrawingEvent;
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

// @Configuration : 빈을 정의하는 설정 클래스.
@Configuration
// @EnableCaching : @Cacheable, @CacheEvict 같은 스프링 캐시 어노테이션을 동작하게 켠다.
//                  (이게 없으면 CouponIssueService의 캐시 어노테이션이 무시된다)
@EnableCaching
public class RedisConfig {

    // @Value : application.yml 의 Redis 접속 정보를 주입.
    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    // --- 쿠폰 도메인 캐시 이름 상수 ---
    public static final String COUPON_ITEM_CACHE_NAME = "coupon-item-cache";
    public static final String COUPON_LIST_CACHE_NAME = "user-coupons-list-cache";

    // @Bean : Redis 연결 팩토리를 빈으로 등록(Lettuce는 비동기/논블로킹 Redis 클라이언트).
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        return new LettuceConnectionFactory(host, port);
    }

    // @Bean : 쿠폰 재고용 기본 RedisTemplate. 키/값을 모두 문자열로 직렬화한다.
    // (CouponStockRedisRepository 의 Lua 스크립트가 이 템플릿으로 재고를 다룬다)
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        // 직렬화기 지정: 지정하지 않으면 JDK 직렬화가 적용돼 redis-cli로 값을 읽기 어려워진다.
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

        //GenericJackson2JsonRedisSerializer serializer = createGenericSerializer();
        Jackson2JsonRedisSerializer<RefreshToken> jacksonSerializer =
                new Jackson2JsonRedisSerializer<>(RefreshToken.class);

        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule()); // JavaTimeModule 등록
        // TODO : Deprecated 기능 리팩토링
        jacksonSerializer.setObjectMapper(om);

        RedisTemplate<String, RefreshToken> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jacksonSerializer);
        template.setHashValueSerializer(jacksonSerializer);
        template.afterPropertiesSet();
        return template;
    }

    @Bean(name = "raffleDrawingRedisTemplate")
    public RedisTemplate<String, RaffleDrawingEvent> raffleDrawingRedisTemplate(RedisConnectionFactory factory) {

        //GenericJackson2JsonRedisSerializer serializer = createGenericSerializer();
        Jackson2JsonRedisSerializer<RaffleDrawingEvent> jacksonSerializer =
                new Jackson2JsonRedisSerializer<>(RaffleDrawingEvent.class);

        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule()); // JavaTimeModule 등록
        // TODO : Deprecated 기능 리팩토링
        jacksonSerializer.setObjectMapper(om);

        RedisTemplate<String, RaffleDrawingEvent> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jacksonSerializer);
        template.setHashValueSerializer(jacksonSerializer);
        template.afterPropertiesSet();
        return template;
    }

    private GenericJackson2JsonRedisSerializer createGenericSerializer() {
        return new GenericJackson2JsonRedisSerializer(createPolymorphicObjectMapper());
    }

    private ObjectMapper createPolymorphicObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        // Java 8+ 날짜 타입 처리
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // 폴리모픽 허용 대상만 명시적으로 등록(화이트리스트)
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(RaffleDrawingEvent.class)
                .allowIfBaseType(RefreshToken.class)
                .build();

        // 다형성 직렬화 활성화(필요한 경우에만)
        objectMapper.activateDefaultTyping(
                ptv,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        return objectMapper;
    }

    // @Bean(name = ...) : 같은 타입의 빈이 여러 개라 이름으로 구분한다.
    // 주입받는 쪽은 변수명을 'couponCacheTemplate'으로 맞추거나 @Qualifier로 이 빈을 지정한다.
    // 쿠폰 발급 응답(DTO)을 JSON으로 캐싱하기 위한 전용 템플릿.
    @Bean(name = "couponCacheTemplate")
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
        // TODO : Deprecated 기능 리팩토링
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
    // @Bean(name = "couponCacheManager") : @Cacheable/@CacheEvict 가 실제로 값을 넣고 빼는 저장소 관리자.
    // 캐시 이름별로 TTL(만료시간)을 다르게 줘서, 오래된 캐시가 무한정 남지 않도록 한다.
    @Bean(name = "couponCacheManager")
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
