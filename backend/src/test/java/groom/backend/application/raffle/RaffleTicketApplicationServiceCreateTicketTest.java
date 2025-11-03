package groom.backend.application.raffle;

import groom.backend.domain.auth.entity.User;
import groom.backend.domain.raffle.entity.Raffle;
import groom.backend.interfaces.raffle.persistence.Entity.RaffleTicketJpaEntity;
import groom.backend.interfaces.raffle.persistence.repository.springData.SpringDataRaffleTicketRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.*;


@Transactional
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public class RaffleTicketApplicationServiceCreateTicketTest {

    @Autowired
    private RaffleTicketAllocationService allocationService;

    @Autowired
    private RaffleTicketApplicationService raffleTicketService;

    @Autowired
    private SpringDataRaffleTicketRepository raffleTicketRepo;

    @Autowired
    private RaffleApplicationService raffleApplicationService;


    @Test
    void createTicket_savesToDatabase_realDb() {
        Raffle raffle = mock(Raffle.class);
        User user = mock(User.class);

        given(raffle.getRaffleId()).willReturn(1L);
        given(user.getId()).willReturn(1L);

        Raffle findRaffle = raffleApplicationService.findByRaffleProductId("4");

        raffleApplicationService.validateRaffleForEntry(findRaffle);

        raffleTicketService.validateUserEntryLimit(findRaffle, user, 1);

        raffleTicketService.createTicket(findRaffle, user);

        int count = raffleTicketRepo.findAll().size();

        assertEquals(2, count);
    }

    @Test
    void createTicket_allocatesNumberAndSavesTicket() {
        //given
        Raffle raffle = mock(Raffle.class); // Raffle 도메인 객체를 목으로 생성 — 필요한 메서드만 스텁할 예정
        User user = mock(User.class); // User 도메인 객체를 목으로 생성

        given(raffle.getRaffleId()).willReturn(10L);
        given(user.getId()).willReturn(100L);

        // allocationService에서 5L을 할당한다고 가정
        given(allocationService.allocateNextTicketNumber(10L)).willReturn(5L);

        // save는 전달된 엔티티를 그대로 반환
        given(raffleTicketRepo.save(any(RaffleTicketJpaEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        // when
        RaffleTicketJpaEntity result = raffleTicketService.createTicket(raffle, user);

        // then
        assertNotNull(result);
        assertEquals(10L, result.getRaffleId());
        assertEquals(100L, result.getUserId());
        assertEquals(5L, result.getTicketNumber());
        assertNotNull(result.getCreatedAt());
        assertTrue(result.getCreatedAt().isBefore(LocalDateTime.now().plusSeconds(2)));

        ArgumentCaptor<RaffleTicketJpaEntity> captor = ArgumentCaptor.forClass(RaffleTicketJpaEntity.class);
        then(raffleTicketRepo).should().save(captor.capture());
        RaffleTicketJpaEntity saved = captor.getValue();
        assertEquals(5L, saved.getTicketNumber());
        assertEquals(10L, saved.getRaffleId());
        assertEquals(100L, saved.getUserId());

        then(allocationService).should().allocateNextTicketNumber(10L);
    }
}
