package groom.backend.application.coupon;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
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
 * 확정된 발급을 한 문장으로 저장한다
 *
 * <p>여기에 조건도 검사도 없다. 게이트를 통과해 여기 왔다는 것이 곧 "발급해도 된다" 는 답이다
 * 컨슈머가 {@code COUNT} 로 재고를 다시 검사하면 세는 것과 넣는 것 사이에 남이 끼어들고,
 * 그것을 막으려면 그 구간을 배타적으로 만들어야 한다 - 방금 걷어낸 행 잠금이 그대로 돌아온다
 *
 * <p><b>{@code coupon} 테이블을 건드리지 않는다.</b> {@code UPDATE coupon SET quantity = quantity - 1}
 * 이 사라진 것이 이 라운드의 본체다. 그 문장이 있는 한 같은 쿠폰의 모든 확정이 한 행을 두고 줄을 서므로,
 * 컨슈머를 몇 개로 늘려도 그 행이 상한을 정한다
 *
 * <p>배치가 줄이는 것은 커넥션 획득 · 트랜잭션 · 네트워크 왕복이고,
 * 행 INSERT · 인덱스 갱신 · WAL 기록은 그대로다. 그래서 배치를 키워도 한계가 있고
 * 그 지점이 어디인지가 배치 크기 스윕의 측정 대상이다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueBatchWriter {

    // ON CONFLICT DO NOTHING - 재처리를 전제로 짠다
    // 500 건 중 1 건이 실패하면 트랜잭션 전체가 롤백되고 오프셋도 커밋되지 않아 500 건이 다시 온다
    // 그때 이미 들어간 행이 제약 위반을 내면 재처리가 영원히 성공하지 못한다
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
     * 한 쿠폰의 확정분을 한 문장으로 넣고, 이 배치가 소유한 requestId 를 돌려준다
     *
     * <p>소유 = 이번 문장이 넣은 행 + 이미 있는 행의 request_id 가 같은 요청. 뒤의 경우가 재전달이다 -
     * 앞 시도가 커밋하고 뒤 단계에서 실패해 같은 배치가 다시 왔으므로 이미 처리한 요청이고, 재고를 되돌리면 안 된다
     * 소유하지 못한 요청은 request_id 가 다른 행(또는 구 행)이 가로챈 진짜 중복이다
     *
     * <p>충돌이 없는 정상 경로는 문장 1 개로 끝난다. 확인 쿼리는 충돌이 난 사용자가 있을 때만 붙는다
     *
     * @param requestIdByUser 사용자 → 요청 ID. 한 사용자에 요청이 둘이면 앞의 것만 넣는다(호출부가 정한다)
     * @return 행을 소유한 requestId 집합
     */
    // REQUIRES_NEW - 컨슈머 스레드에는 바깥 트랜잭션이 없지만, 있어도 합류하지 않게 못 박는다
    // timeout : 파티션당 컨슈머가 1 명이라 이 구간이 늘어지면 그 파티션의 뒤 이벤트가 전부 함께 멈춘다
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public Set<String> insertAll(Long couponId, LocalDateTime expiresAt, Map<Long, String> requestIdByUser) {
        if (requestIdByUser.isEmpty()) {
            return Set.of();
        }
        List<Long> distinct = new ArrayList<>(requestIdByUser.keySet());

        StringBuilder sql = new StringBuilder(INSERT_PREFIX);
        for (int i = 0; i < distinct.size(); i++) {
            sql.append(i == 0 ? "(?, ?, ?, ?, true, ?)" : ", (?, ?, ?, ?, true, ?)");
        }
        sql.append(INSERT_SUFFIX);

        Timestamp createdAt = Timestamp.valueOf(LocalDateTime.now());
        Timestamp deletedAt = Timestamp.valueOf(expiresAt);

        Set<Long> insertedUsers = jdbcTemplate.query(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql.toString());
            int p = 1;
            for (Long userId : distinct) {
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

        Set<String> owned = new LinkedHashSet<>();
        List<Long> conflicted = new ArrayList<>();
        for (Long userId : distinct) {
            if (insertedUsers.contains(userId)) {
                owned.add(requestIdByUser.get(userId));
            } else {
                conflicted.add(userId);
            }
        }
        if (!conflicted.isEmpty()) {
            owned.addAll(findOwnedAmongConflicted(couponId, conflicted, requestIdByUser));
        }
        return owned;
    }

    // 충돌한 사용자의 기존 행에서 request_id 를 읽어 이번 요청과 같은 것만 고른다
    private Set<String> findOwnedAmongConflicted(Long couponId, List<Long> conflicted,
                                                 Map<Long, String> requestIdByUser) {
        String placeholders = String.join(", ", Collections.nCopies(conflicted.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(couponId);
        args.addAll(conflicted);
        Set<String> owned = new LinkedHashSet<>();
        jdbcTemplate.query(
                "SELECT user_id, request_id FROM coupon_issue WHERE coupon_id = ? AND user_id IN (" + placeholders + ")",
                rs -> {
                    String existing = rs.getString(2);
                    if (existing != null && existing.equals(requestIdByUser.get(rs.getLong(1)))) {
                        owned.add(existing);
                    }
                }, args.toArray());
        return owned;
    }
}
