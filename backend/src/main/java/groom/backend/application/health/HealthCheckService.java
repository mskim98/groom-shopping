package groom.backend.application.health;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

@Service
public class HealthCheckService {
    @PersistenceContext // 엔티티 매니저 주입
    private EntityManager em;

    public boolean isConnected() {
        try {
            return em.createNativeQuery("SELECT 1").getSingleResult() != null;
        } catch (Exception e) {
            return false;
        }
    }

}
