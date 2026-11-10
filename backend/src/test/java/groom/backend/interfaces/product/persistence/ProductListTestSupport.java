package groom.backend.interfaces.product.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;

/** 목록 · 검색 시험이 같이 쓰는 시험 자료 만들기와 EXPLAIN 실행 */
public final class ProductListTestSupport {

    /** 네 시험 클래스가 같은 스프링 컨텍스트를 쓰도록 같은 값으로 건다 */
    public static final String CAP_PROPERTY = "product.list.count-cap=50";
    public static final String INSPECTOR_PROPERTY =
            "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                    + "groom.backend.interfaces.product.persistence.RecordingStatementInspector";

    private ProductListTestSupport() {
    }

    /** 다른 시험 · 시드 상품과 겹치지 않는 가격(검색 조건으로 시험 행만 고르는 데 쓴다) */
    public static int uniquePrice() {
        return ThreadLocalRandom.current().nextInt(5_000_000, 9_000_000);
    }

    public static List<UUID> save(SpringDataProductRepository repo, List<String> names, int price, String category) {
        List<ProductJpaEntity> rows = new ArrayList<>();
        for (String name : names) {
            rows.add(ProductJpaEntity.builder()
                    .id(UUID.randomUUID()).name(name).description("d").price(price).stock(10)
                    .isActive(true).category(category).status("AVAILABLE").thresholdValue(10)
                    .build());
        }
        return repo.saveAll(rows).stream().map(ProductJpaEntity::getId).toList();
    }

    /** n 개의 이름 "<prefix>-<번호>" 를 만든다 */
    public static List<String> numberedNames(String prefix, int n) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            names.add(prefix + "-" + i);
        }
        return names;
    }

    /**
     * 저장소가 낸 SQL 의 ? 자리를 values 로 차례로 채워 enable_seqscan=off 로 EXPLAIN 한 계획 전체를 돌려준다.
     * ? 개수가 values 개수와 다르면 SQL 이 바뀐 것이므로 시험이 실패한다
     */
    public static String explainWithoutSeqScan(JdbcTemplate jdbc, String sql, List<Object> values) {
        String filled = fill(sql, values);
        return jdbc.execute((ConnectionCallback<String>) con -> {
            boolean auto = con.getAutoCommit();
            con.setAutoCommit(false);
            try (var st = con.createStatement()) {
                st.execute("SET LOCAL enable_seqscan = off");
                StringBuilder plan = new StringBuilder();
                try (var rs = st.executeQuery("EXPLAIN " + filled)) {
                    while (rs.next()) {
                        plan.append(rs.getString(1)).append('\n');
                    }
                }
                return plan.toString();
            } finally {
                con.rollback();
                con.setAutoCommit(auto);
            }
        });
    }

    static String fill(String sql, List<Object> values) {
        StringBuilder out = new StringBuilder();
        int next = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '?') {
                if (next >= values.size()) {
                    throw new IllegalStateException("SQL has more ? than values (" + values.size() + "): " + sql);
                }
                Object v = values.get(next++);
                out.append(v instanceof Number ? v.toString() : "'" + v.toString().replace("'", "''") + "'");
            } else {
                out.append(c);
            }
        }
        if (next != values.size()) {
            throw new IllegalStateException("SQL has " + next + " ? but " + values.size() + " values: " + sql);
        }
        return out.toString();
    }
}
