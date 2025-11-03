package groom.backend.interfaces.raffle.persistence;

import groom.backend.interfaces.raffle.dto.request.RaffleSearchRequest;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public class RaffleSpecs {
    public static Specification<RaffleJpaEntity> of(RaffleSearchRequest cond) {
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
}
