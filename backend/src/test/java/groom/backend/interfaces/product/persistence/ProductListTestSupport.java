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
     * 표가 작으면 플래너가 id 정렬용 기본키 인덱스를 훑는 계획을 골라 이름 인덱스가 계획에 안 나타난다 - 실제 규모처럼 보이게 채우고 통계를 갱신한다
     * 이름은 "<prefix>-<md5>"(16진수라 u 가 없다)이라 시험이 찾는 키워드와 겹치지 않는다. 시험이 끝나면 {@link #dropFiller} 로 지운다
     */
    public static void fillProductTable(JdbcTemplate jdbc, String prefix, int rows) {
        jdbc.update("INSERT INTO product (id, name, description, price, stock, is_active, category, status, threshold_value,"
                + " created_at, updated_at, version, flash_sale, stock_mode)"
                + " SELECT gen_random_uuid(), ? || '-' || md5(g::text), 'd', 1000 + (g % 500), 10, true, 'GENERAL', 'AVAILABLE', 10,"
                + " now(), now(), 0, false, 'REDIS' FROM generate_series(1, ?) g", prefix, rows);
        jdbc.execute("ANALYZE product");
    }

    public static void dropFiller(JdbcTemplate jdbc, String prefix) {
        jdbc.update("DELETE FROM product WHERE name LIKE ?", prefix + "-%");
        // 지운 행의 흔적으로 표와 인덱스가 부풀면(40행짜리 인덱스가 2.6MB) 인덱스 읽기 비용이 커져 다른 시험의 EXPLAIN 이 달라진다
        // 일반 VACUUM 은 인덱스를 줄이지 못하므로 FULL 로 표와 인덱스를 다시 쓴다
        jdbc.execute("VACUUM (FULL, ANALYZE) product");
    }

    /**
     * 저장소가 낸 SQL 의 ? 자리를 values 로 차례로 채워 enable_seqscan=off 로 EXPLAIN 한 계획 전체를 돌려준다
     * ? 개수가 values 개수와 다르면 SQL 이 바뀐 것이므로 시험이 실패한다
     * alsoOff 로 받은 계획 설정(enable_sort 등)도 같은 트랜잭션 안에서만 끈다
     */
    public static String explainWithoutSeqScan(JdbcTemplate jdbc, String sql, List<Object> values, String... alsoOff) {
        String filled = fill(sql, values);
        return jdbc.execute((ConnectionCallback<String>) con -> {
            boolean auto = con.getAutoCommit();
            con.setAutoCommit(false);
            try (var st = con.createStatement()) {
                st.execute("SET LOCAL enable_seqscan = off");
                for (String setting : alsoOff) {
                    st.execute("SET LOCAL " + setting + " = off");
                }
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
