package groom.backend.interfaces.product.persistence;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Hibernate 가 DB 로 보내는 SQL 문을 그대로 모은다. 목록 · 검색 시험이 "몇 문이 나갔는지" 와 "EXPLAIN 에 넘길 실제 SQL" 을 여기서 얻는다
 * (손으로 쓴 SQL 은 저장소가 내는 SQL 과 어긋날 수 있다). 속성 hibernate.session_factory.statement_inspector 에 클래스 이름으로 건다
 */
public class RecordingStatementInspector implements StatementInspector {

    private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        STATEMENTS.add(sql);
        return sql;
    }

    public static void clear() {
        STATEMENTS.clear();
    }

    /** product 표를 읽는 SELECT 문만 (소문자로 비교) */
    public static List<String> productSelects() {
        return STATEMENTS.stream()
                .filter(s -> s.toLowerCase().contains("from product") || s.toLowerCase().contains("from public.product"))
                .toList();
    }

    /** product 표를 읽는 SELECT 중 count 문이 아닌 것(목록 · 검색 본문) */
    public static List<String> productListSelects() {
        return productSelects().stream().filter(s -> !s.toLowerCase().contains("count(")).toList();
    }
}
