package groom.backend.infrastructure.cache;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

// 상품 캐시 시험이 SQL 문 수를 세는 도구 - 데이터소스를 감싸 prepareStatement 로 나간 SQL 을 모은다(JPA · JdbcTemplate 모두 잡힌다)
final class CacheTestSupport {

    static final List<String> SQL = new CopyOnWriteArrayList<>();

    private CacheTestSupport() {
    }

    static long countContaining(String fragment) {
        return SQL.stream().filter(sql -> sql.toLowerCase().contains(fragment)).count();
    }

    /** 상품 본문을 읽는 SQL(JPA 엔티티 로드 - description 열을 가져온다)만 센다 */
    static long bodySelects() {
        return SQL.stream().filter(sql -> sql.toLowerCase().contains("from product ") && sql.toLowerCase().contains("description")).count();
    }

    @TestConfiguration
    static class SqlCounting {

        @Bean
        static BeanPostProcessor sqlCountingPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof DataSource dataSource) || Proxy.isProxyClass(bean.getClass())) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(bean.getClass().getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                        Object result = invoke(dataSource, method, args);
                        if ("getConnection".equals(method.getName()) && result instanceof Connection connection) {
                            return Proxy.newProxyInstance(bean.getClass().getClassLoader(), new Class<?>[] {Connection.class},
                                    (p, m, a) -> {
                                        if ("prepareStatement".equals(m.getName()) && a != null && a[0] instanceof String sql) {
                                            SQL.add(sql);
                                        }
                                        return invoke(connection, m, a);
                                    });
                        }
                        return result;
                    });
                }
            };
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    static String uniqueName() {
        return "cache-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
