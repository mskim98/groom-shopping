package groom.backend.infrastructure.kafka.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

@DisplayName("쿠폰 지연 Processor")
class CouponDelayProcessorTest {

    private static final String INPUT_TOPIC = "coupon-delay-events";
    private static final String OUTPUT_TOPIC = "coupon-activate-events";

    private TopologyTestDriver driver;
    private TestInputTopic<String, CouponDelayEvent> input;
    private TestOutputTopic<String, CouponDelayEvent> output;

    @BeforeEach
    void setUp() {
        StreamsBuilder builder = new StreamsBuilder();
        new CouponDelayStreamProcessor().couponDelayStream(builder);

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "coupon-delay-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");

        driver = new TopologyTestDriver(builder.build(), props);

        JsonSerializer<CouponDelayEvent> serializer = new JsonSerializer<>();
        JsonDeserializer<CouponDelayEvent> deserializer = new JsonDeserializer<>(CouponDelayEvent.class);
        deserializer.addTrustedPackages("*");

        input = driver.createInputTopic(INPUT_TOPIC,
                Serdes.String().serializer(), serializer);
        output = driver.createOutputTopic(OUTPUT_TOPIC,
                Serdes.String().deserializer(), deserializer);
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    @Test
    @DisplayName("지연 시간이 지나기 전에는 출력 토픽으로 내보내지 않는다")
    void doesNotForwardBeforeDelayElapses() {
        input.pipeInput("1", new CouponDelayEvent(1L, 5_000L, System.currentTimeMillis()));
        driver.advanceWallClockTime(Duration.ofSeconds(1));

        assertThat(output.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("지연 시간이 지나면 punctuate 가 출력 토픽으로 내보낸다")
    void forwardsAfterDelayElapses() {
        input.pipeInput("1", new CouponDelayEvent(1L, 3_000L, System.currentTimeMillis()));
        driver.advanceWallClockTime(Duration.ofSeconds(5));

        List<CouponDelayEvent> forwarded = output.readValuesToList();
        assertThat(forwarded).hasSize(1);
        assertThat(forwarded.get(0).getCouponId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("같은 초에 도달하는 여러 이벤트가 덮어쓰기로 유실되지 않는다")
    void accumulatesEventsInSameSecond() {
        long now = System.currentTimeMillis();
        input.pipeInput("1", new CouponDelayEvent(1L, 3_000L, now));
        input.pipeInput("2", new CouponDelayEvent(2L, 3_000L, now));
        driver.advanceWallClockTime(Duration.ofSeconds(5));

        assertThat(output.readValuesToList()).hasSize(2);
    }
}
