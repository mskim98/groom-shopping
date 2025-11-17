package groom.backend.interfaces.raffle.persistence.repository.jpa;

import groom.backend.domain.raffle.criteria.RaffleSearchCriteria;
import groom.backend.domain.raffle.entity.Raffle;
import groom.backend.domain.raffle.enums.RaffleStatus;
import groom.backend.domain.raffle.repository.RaffleRepository;
import groom.backend.interfaces.raffle.persistence.Entity.RaffleJpaEntity;
import groom.backend.interfaces.raffle.persistence.repository.springData.SpringDataRaffleRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class JpaRaffleRepository implements RaffleRepository {

    private final SpringDataRaffleRepository raffleRepository;

    public JpaRaffleRepository(SpringDataRaffleRepository raffleRepository) {
        this.raffleRepository = raffleRepository;
    }

    @Override
    public Optional<Raffle> findById(Long id) {
        return raffleRepository.findById(id).map(this::toDomain);
    }

    @Override
    public Raffle save(Raffle raffle) {
        RaffleJpaEntity saved = raffleRepository.save(toEntity(raffle));
        return toDomain(saved);
    }

    @Override
    public boolean existsByRaffleProductId(UUID raffleProductId) {
        return raffleRepository.existsByRaffleProductId(raffleProductId);
    }

    @Override
    public void deleteById(Long raffleId) {
        raffleRepository.deleteById(raffleId);
    }

    @Override
    public Page<Raffle> search(RaffleSearchCriteria cond, Pageable pageable) {
        /*
         * 1) 동적 검색 조건(Specification) 생성
         * - RaffleSpecs.of(cond)는 전달된 검색 조건(RaffleSearchRequest)을 기반으로
         *   JPA Specification\<RaffleJpaEntity\>을 만든다.
         * - Specification은 엔티티 필드에 대한 where 절 조합(AND, OR 등)을 지연 생성하는 객체다.
         * - cond가 null이거나 내부 필드가 비어있으면 항상 true인 conjunction을 반환하도록 구현되어야 한다.
         */
        Specification<RaffleJpaEntity> spec = toSpecification(cond);
        /*
         * 2) Spring Data JPA 리포지토리에 Specification + Pageable로 쿼리 실행
         * - raffleRepository.findAll(spec, pageable)은 DB에 실제 SELECT 쿼리를 날린다.
         * - pageable은 페이지 번호, 사이즈, 정렬 정보를 포함한다.
         * - 반환되는 page는 JPA 엔티티 타입(RaffleJpaEntity)의 Page\<RaffleJpaEntity\>이다.
         * - 이 시점에 페이징에 필요한 total count 쿼리도 내부적으로 실행될 수 있다(조회 전략에 따라).
         */
        Page<RaffleJpaEntity> page = raffleRepository.findAll(spec, pageable);
        /*
         * 3) 엔티티 -> 도메인 모델 매핑
         * - persistence 계층의 엔티티(RaffleJpaEntity)를 도메인 객체(Raffle)로 변환한다.
         * - page.stream()으로 현재 페이지의 요소만 변환함(전체 데이터가 아닌 조회된 페이지 데이터만).
         * - this::toDomain은 각 RaffleJpaEntity를 Raffle 도메인 객체로 변환하는 메서드 레퍼런스이다.
         * - Collectors.toList()로 변환된 도메인 리스트를 만든다.
         *
         * 주의:
         * - 매핑 과정에서 null 필드, 날짜 포맷, enum 변환 등에 주의해야 한다.
         * - toDomain이 무거운 연산(예: 추가 DB 호출)을 하면 성능 이슈가 발생할 수 있으므로 피해야 한다.
         */
        List<Raffle> domainList = page.stream().map(this::toDomain).collect(Collectors.toList());
        /*
         * 4) 도메인 객체 리스트로 Page\<Raffle\> 인스턴스 생성 후 반환
         * - PageImpl\<Raffle\> 생성자는 다음을 필요로 한다:
         *     1) content: 현재 페이지의 도메인 리스트(domainList)
         *     2) pageable: 원래 클라이언트가 요청한 페이징 정보(pageable) -> 클라이언트 응답에 동일한 페이징 메타정보 유지
         *     3) total: 전체 검색 결과 수(page.getTotalElements()) -> 클라이언트에서 총 페이지 수 계산에 사용
         * - 이렇게 하면 컨트롤러/서비스에서 Page\<Raffle\> 형태로 응답을 그대로 반환할 수 있다.
         *
         * 성능/정합성 참고:
         * - page.getTotalElements()는 Repository가 실행한 count 쿼리의 결과를 사용한다.
         * - count 쿼리가 비용이 크다면 별도 최적화(예: keyset pagination, count 쿼리 생략 옵션)를 고려해야 한다.
         * - Pageable의 정렬 필드명이 엔티티 필드명과 일치하는지 확인해야 한다(오타 시 예외 발생).
         */
        return new PageImpl<>(domainList, pageable, page.getTotalElements());
    }

    // 추첨용 상품 Id 로 추첨 정보 받아오기
    @Override
    public Optional<Raffle> findByRaffleProductId(UUID raffleProductId) {
        return raffleRepository.findByRaffleProductId(raffleProductId);
    }

    @Override
    public Page<Raffle> findByStatusAndEntryStartAtBefore(RaffleStatus status, LocalDateTime now, Pageable pageable) {
        Page<RaffleJpaEntity> page = raffleRepository.findAllByStatusAndEntryStartAtBefore(status, now, pageable);
        return page.map(this::toDomain);
    }

    @Override
    public Page<Raffle> findAllByStatusAndEntryEndAtBefore(RaffleStatus status, LocalDateTime now, Pageable pageable) {
        Page<RaffleJpaEntity> page = raffleRepository.findAllByStatusAndEntryEndAtBefore(status, now, pageable);
        return page.map(this::toDomain);
    }

    @Override
    public Page<Raffle> findAllByStatusAndRaffleDrawAtBefore(RaffleStatus status, LocalDateTime now, Pageable pageable) {
        Page<RaffleJpaEntity> page = raffleRepository.findAllByStatusAndRaffleDrawAtBefore(status, now, pageable);
        return page.map(this::toDomain);
    }

    // 도메인 기준을 Specification으로 변환 (인터페이스 레이어에 위치하므로 엔티티 참조 가능)
    private Specification<RaffleJpaEntity> toSpecification(RaffleSearchCriteria cond) {
        return (root, query, cb) -> {
            if (cond == null) {
                return cb.conjunction();
            }

            List<Predicate> predicates = new ArrayList<>();

            if (cond.getTitle() != null && !cond.getTitle().isEmpty()) {
                predicates.add(cb.like(cb.lower(root.get("title")), "%" + cond.getTitle().toLowerCase() + "%"));
            }
            if (cond.getRaffleProductId() != null && !cond.getRaffleProductId().isEmpty()) {
                predicates.add(cb.equal(root.get("raffleProductId"), cond.getRaffleProductId()));
            }
            if (cond.getStatus() != null) {
                predicates.add(cb.equal(root.get("status"), cond.getStatus()));
            }
            if (cond.getEntryStartFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("entryStartAt"), cond.getEntryStartFrom()));
            }
            if (cond.getEntryStartTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("entryStartAt"), cond.getEntryStartTo()));
            }
            if (cond.getRaffleDrawFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("raffleDrawAt"), cond.getRaffleDrawFrom()));
            }
            if (cond.getRaffleDrawTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("raffleDrawAt"), cond.getRaffleDrawTo()));
            }

            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Raffle toDomain(RaffleJpaEntity e) {
        return new Raffle(e.getRaffleId(),
                e.getRaffleProductId(),
                e.getWinnerProductId(),
                e.getTitle(),
                e.getDescription(),
                e.getWinnersCount(),
                e.getMaxEntriesPerUser(),
                e.getEntryStartAt(),
                e.getEntryEndAt(),
                e.getRaffleDrawAt(),
                e.getStatus(),
                e.getCreatedAt(),
                e.getUpdatedAt()
                );
    }

    private RaffleJpaEntity toEntity(Raffle raffle) {
        return RaffleJpaEntity.builder()
                .raffleId(raffle.getRaffleId())
                .raffleProductId(raffle.getRaffleProductId())
                .winnerProductId(raffle.getWinnerProductId())
                .title(raffle.getTitle())
                .description(raffle.getDescription())
                .winnersCount(raffle.getWinnersCount())
                .maxEntriesPerUser(raffle.getMaxEntriesPerUser())
                .entryStartAt(raffle.getEntryStartAt())
                .entryEndAt(raffle.getEntryEndAt())
                .raffleDrawAt(raffle.getRaffleDrawAt())
                .status(raffle.getStatus())
                .createdAt(raffle.getCreatedAt())
                .updatedAt(raffle.getUpdatedAt())
                .build();
    }


}
