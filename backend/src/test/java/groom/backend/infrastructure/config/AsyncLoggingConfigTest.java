package groom.backend.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.mock.env.MockEnvironment;

// 요청 스레드가 로그를 쓸 때 appender 락을 기다려 DB 커넥션을 쥔 채 멈추지 않도록, dev · prod 의 콘솔 · 파일 로그가 AsyncAppender 를 거치는지 본다
// logback-spring.xml 을 스프링 부트와 같은 방식(SpringBootJoranConfigurator, 활성 프로파일 지정)으로 새 로거 컨텍스트에 읽는다
@DisplayName("dev · prod 로그의 비동기 appender")
class AsyncLoggingConfigTest {

    private static LoggerContext configure(String profile) throws Exception {
        URL config = AsyncLoggingConfigTest.class.getClassLoader().getResource("logback-spring.xml");
        assertThat(config).as("logback-spring.xml").isNotNull();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        LoggerContext context = new LoggerContext();
        // 새 컨텍스트에는 MDC 어댑터가 없어 로그를 쓰는 순간 appender 가 NullPointerException 으로 실패한다
        context.setMDCAdapter(new LogbackMDCAdapter());
        // 스프링 부트의 <springProfile> 태그를 읽는 구성기는 패키지 전용 클래스라 리플렉션으로 만들고, 공개 상위 클래스로 쓴다
        Constructor<?> constructor = Class.forName("org.springframework.boot.logging.logback.SpringBootJoranConfigurator")
                .getDeclaredConstructor(LoggingInitializationContext.class);
        constructor.setAccessible(true);
        JoranConfigurator configurator = (JoranConfigurator) constructor.newInstance(new LoggingInitializationContext(environment));
        configurator.setContext(context);
        configurator.doConfigure(config);
        return context;
    }

    // 로거에 붙은 appender 와 additivity 로 이어지는 상위 로거의 appender 전부
    private static List<Appender<?>> effectiveAppenders(Logger logger) {
        List<Appender<?>> appenders = new ArrayList<>();
        for (Logger current = logger; current != null; current = parentOf(current)) {
            for (Iterator<Appender<ILoggingEvent>> it = current.iteratorForAppenders(); it.hasNext(); ) {
                appenders.add(it.next());
            }
            if (!current.isAdditive()) {
                break;
            }
        }
        return appenders;
    }

    private static Logger parentOf(Logger logger) {
        String name = logger.getName();
        if (name.equals(Logger.ROOT_LOGGER_NAME)) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        return logger.getLoggerContext().getLogger(dot < 0 ? Logger.ROOT_LOGGER_NAME : name.substring(0, dot));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"dev", "prod"})
    @DisplayName("루트와 groom.backend 로거에 닿는 appender 는 전부 기본 큐 · 기본 폐기 문턱값 · neverBlock 꺼짐의 AsyncAppender 다")
    void 로거에_닿는_appender는_전부_AsyncAppender다(String profile) throws Exception {
        LoggerContext context = configure(profile);
        try {
            for (String name : List.of(Logger.ROOT_LOGGER_NAME, "groom.backend")) {
                List<Appender<?>> appenders = effectiveAppenders(context.getLogger(name));
                assertThat(appenders).as("%s 로거에 닿는 appender (%s)", name, profile).isNotEmpty()
                        .allSatisfy(appender -> assertThat(appender).isInstanceOf(AsyncAppender.class));
                for (Appender<?> appender : appenders) {
                    AsyncAppender async = (AsyncAppender) appender;
                    assertThat(async.isNeverBlock()).as("neverBlock").isFalse();
                    assertThat(async.getQueueSize()).as("기본 큐 크기").isEqualTo(256);
                    // 기본 문턱값은 큐의 20% - 큐가 80% 차면 INFO 이하만 버려지고 WARN · ERROR 는 버려지지 않는다
                    assertThat(async.getDiscardingThreshold()).as("기본 폐기 문턱값").isEqualTo(256 / 5);
                }
            }
        } finally {
            context.stop();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"dev", "prod"})
    @DisplayName("AsyncAppender 는 원래의 콘솔과 롤링 파일 appender 를 감싼다")
    void AsyncAppender는_콘솔과_롤링파일을_감싼다(String profile) throws Exception {
        LoggerContext context = configure(profile);
        try {
            List<Appender<?>> wrapped = new ArrayList<>();
            for (Appender<?> appender : effectiveAppenders(context.getLogger(Logger.ROOT_LOGGER_NAME))) {
                assertThat(appender).isInstanceOf(AsyncAppender.class);
                for (Iterator<Appender<ILoggingEvent>> it = ((AsyncAppender) appender).iteratorForAppenders(); it.hasNext(); ) {
                    wrapped.add(it.next());
                }
            }
            assertThat(wrapped).hasAtLeastOneElementOfType(ConsoleAppender.class).hasAtLeastOneElementOfType(RollingFileAppender.class);
        } finally {
            context.stop();
        }
    }

    // groom.backend 로거에 닿는 콘솔 appender - 비동기 appender 가 감싼 것도 찾는다
    private static ConsoleAppender<ILoggingEvent> consoleOf(Logger logger) {
        for (Appender<?> appender : effectiveAppenders(logger)) {
            if (appender instanceof ConsoleAppender<?> console) {
                return (ConsoleAppender<ILoggingEvent>) console;
            }
            for (Iterator<Appender<ILoggingEvent>> it = ((AsyncAppender) appender).iteratorForAppenders(); it.hasNext(); ) {
                if (it.next() instanceof ConsoleAppender<ILoggingEvent> console) {
                    return console;
                }
            }
        }
        throw new AssertionError("콘솔 appender 가 닿지 않는다: " + logger.getName());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"dev", "prod"})
    @DisplayName("groom.backend 의 WARN 줄은 컨텍스트가 멈춘 뒤 감싸진 콘솔 appender 에 닿는다")
    void WARN줄은_컨텍스트가_멈춘뒤_콘솔에_닿는다(String profile) throws Exception {
        LoggerContext context = configure(profile);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        String marker = "async-logging-" + UUID.randomUUID();
        try {
            consoleOf(context.getLogger("groom.backend")).setOutputStream(captured);
            context.getLogger("groom.backend").warn(marker);
        } finally {
            // AsyncAppender 는 멈출 때 남은 큐를 비운다
            context.stop();
        }
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains(marker);
    }
}
