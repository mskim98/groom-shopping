package groom.backend.application.coupon;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스트림 컨슈머가 한 쿠폰의 한 배치를 한 트랜잭션으로 저장한다
 *
 * <p>트랜잭션은 {@code SELECT … FROM coupon WHERE id = ? FOR UPDATE} 로 쿠폰 행 잠금을 잡고 시작한다.
 * 잠금은 요청마다가 아니라 <b>배치마다 한 번</b>이다(최대 {@code coupon.issue.batch-size} 건).
 * 잠금 아래서 {@code quantity − 발급 수} 를 읽어 그 자리 수만큼만 넣으므로, Redis 가 끊긴 동안 DB 경로가 낸 발급과
 * 스트림에 남은 요청이 섞여도 합계가 {@code quantity} 를 넘지 않는다. DB 경로({@code DbLockIssueStrategy})가 같은 행 잠금을 쓴다
 *
 * <p><b>{@code coupon} 행을 갱신하지 않는다.</b> {@code quantity} 는 불변 발행 수량이고, 행 잠금은 읽기 전용 직렬화 지점이다.
 * 요청마다 {@code UPDATE coupon SET quantity = quantity - 1} 을 하던 때와 달리 배치 한 번에 잠금 한 번이다
 *
 * <p>배치가 줄이는 것은 커넥션 획득 · 트랜잭션 · 네트워크 왕복이고,
 * 행 INSERT · 인덱스 갱신 · WAL 기록은 그대로다. 그래서 배치를 키워도 한계가 있다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueBatchWriter {

    // ON CONFLICT DO NOTHING - 재처리를 전제로 짠다
    // 커밋 뒤 ack 전에 죽으면 같은 배치가 다시 온다. 그때 이미 들어간 행이 제약 위반을 내면 재처리가 영원히 성공하지 못한다
    //
    // RETURNING user_id - 실제로 들어간 행만 돌려받는다
    // JDBC 배치의 반환 건수에 기대지 않는 이유는 드라이버가 SUCCESS_NO_INFO 를 돌려줄 수 있어서다
    // 그러면 발급과 중복을 가르지 못해 상태 기록이 조용히 틀어진다
    private static final String INSERT_PREFIX =
            "INSERT INTO coupon_issue (coupon_id, user_id, created_at, deleted_at, is_active, request_id) VALUES ";
    private static final String INSERT_SUFFIX =
            " ON CONFLICT (coupon_id, user_id) DO NOTHING RETURNING user_id";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 한 배치의 처리 결과
     *
     * @param couponUsable 쿠폰이 있고 진행 중인가. false 면 아무것도 넣지 않았다
     * @param owned        행을 소유한 requestId - 이번 문장이 넣은 행 + 이미 있는 행의 request_id 가 같은 요청(재전달)
     * @param soldOut      남은 자리가 없어 넣지 못한 requestId. 소유도 중복도 아니다
     *                     (둘 다 아닌 나머지는 다른 요청이 그 사용자의 행을 이미 가진 진짜 중복이다)
     */
    public record InsertOutcome(boolean couponUsable, Set<String> owned, Set<String> soldOut) {
    }

    /**
     * 한 쿠폰의 배치를 쿠폰 행 잠금 아래서 남은 자리까지만 넣는다
     *
     * <p>재전달 판정 : 앞 시도가 커밋하고 뒤 단계에서 죽어 같은 배치가 다시 오면 그 행들은 request_id 가 같다.
     * 그 요청은 이미 처리한 것이라 자리를 다시 쓰지 않고 소유로 돌려준다 - 재고를 되돌리면 안 된다
     *
     * @param requestIdByUser 사용자 → 요청 ID. 입력 순서(스트림 도착 순서)대로 자리를 채운다. 한 사용자에 요청이 둘이면 호출부가 앞의 것만 넘긴다
     */
    // REQUIRES_NEW - 컨슈머 스레드에는 바깥 트랜잭션이 없지만, 있어도 합류하지 않게 못 박는다
    // timeout : 이 구간이 늘어지면 같은 쿠폰의 DB 경로 발급도 행 잠금에서 함께 기다린다
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public InsertOutcome insertAll(Long couponId, Map<Long, String> requestIdByUser) {
        if (requestIdByUser.isEmpty()) {
            return new InsertOutcome(true, Set.of(), Set.of());
        }
        List<Map<String, Object>> coupons = jdbcTemplate.queryForList(
                "SELECT quantity, expire_date, is_active FROM coupon WHERE id = ? FOR UPDATE", couponId);
        if (coupons.isEmpty() || !Boolean.TRUE.equals(coupons.get(0).get("is_active"))) {
            return new InsertOutcome(false, Set.of(), Set.of());
        }
        Map<String, Object> coupon = coupons.get(0);
        long quantity = coupon.get("quantity") == null ? 0L : ((Number) coupon.get("quantity")).longValue();
        LocalDateTime expiresAt = ((java.sql.Date) coupon.get("expire_date")).toLocalDate().atStartOfDay();
        Long issued = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coupon_issue WHERE coupon_id = ?", Long.class, couponId);
        long room = Math.max(0L, quantity - (issued == null ? 0L : issued));

        Set<String> owned = new LinkedHashSet<>();
        List<Long> candidates = new ArrayList<>();
        Map<Long, String> existing = findExisting(couponId, new ArrayList<>(requestIdByUser.keySet()));
        for (Map.Entry<Long, String> entry : requestIdByUser.entrySet()) {
            String existingRequestId = existing.get(entry.getKey());
            if (existingRequestId == null) {
                candidates.add(entry.getKey());
            } else if (existingRequestId.equals(entry.getValue())) {
                owned.add(entry.getValue());
            }
        }

        List<Long> toInsert = candidates.subList(0, (int) Math.min(room, candidates.size()));
        Set<String> soldOut = new LinkedHashSet<>();
        for (Long userId : candidates.subList(toInsert.size(), candidates.size())) {
            soldOut.add(requestIdByUser.get(userId));
        }
        for (Long userId : insert(couponId, expiresAt, toInsert, requestIdByUser)) {
            owned.add(requestIdByUser.get(userId));
        }
        return new InsertOutcome(true, owned, soldOut);
    }

    // 이 배치 사용자들의 기존 행. 행 잠금 아래에서 읽으므로 DB 경로의 발급과 섞여도 한 시점의 값이다
    private Map<Long, String> findExisting(Long couponId, List<Long> userIds) {
        String placeholders = String.join(", ", Collections.nCopies(userIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(couponId);
        args.addAll(userIds);
        Map<Long, String> existing = new HashMap<>();
        jdbcTemplate.query(
                "SELECT user_id, request_id FROM coupon_issue WHERE coupon_id = ? AND user_id IN (" + placeholders + ")",
                rs -> {
                    // request_id 가 NULL 인 구 행은 빈 문자열로 둔다 - 어느 요청과도 같지 않아 중복으로 떨어진다
                    String requestId = rs.getString(2);
                    existing.put(rs.getLong(1), requestId == null ? "" : requestId);
                }, args.toArray());
        return existing;
    }

    private Set<Long> insert(Long couponId, LocalDateTime expiresAt, List<Long> userIds,
                             Map<Long, String> requestIdByUser) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        StringBuilder sql = new StringBuilder(INSERT_PREFIX);
        for (int i = 0; i < userIds.size(); i++) {
            sql.append(i == 0 ? "(?, ?, ?, ?, true, ?)" : ", (?, ?, ?, ?, true, ?)");
        }
        sql.append(INSERT_SUFFIX);

        Timestamp createdAt = Timestamp.valueOf(LocalDateTime.now());
        Timestamp deletedAt = Timestamp.valueOf(expiresAt);

        return jdbcTemplate.query(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql.toString());
            int p = 1;
            for (Long userId : userIds) {
                ps.setLong(p++, couponId);
                ps.setLong(p++, userId);
                ps.setTimestamp(p++, createdAt);
                ps.setTimestamp(p++, deletedAt);
                ps.setString(p++, requestIdByUser.get(userId));
            }
            return ps;
        }, rs -> {
            Set<Long> inserted = new LinkedHashSet<>();
            while (rs.next()) {
                inserted.add(rs.getLong(1));
            }
            return inserted;
        });
    }
}
