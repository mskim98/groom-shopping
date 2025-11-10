package groom.backend.application.raffle;

import groom.backend.domain.auth.entity.User;
import groom.backend.domain.raffle.entity.Raffle;
import groom.backend.interfaces.raffle.persistence.repository.springData.SpringDataRaffleTicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;


@Transactional
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public class RaffleTicketApplicationServiceCreateTicketTest {

    @Autowired
    private RaffleTicketApplicationService raffleTicketService;

    @Autowired
    private SpringDataRaffleTicketRepository raffleTicketRepo;

    @Autowired
    private RaffleApplicationService raffleApplicationService;

    @Autowired
    private RaffleValidationService raffleValidationService;

    @Test
    void createTicket_savesToDatabase_realDb() {
        Raffle raffle = mock(Raffle.class);
        User user = mock(User.class);

        given(raffle.getRaffleId()).willReturn(1L);
        given(user.getId()).willReturn(1L);

        Raffle findRaffle = raffleApplicationService.findByRaffleProductId(UUID.fromString("raffle-product-1"));

        raffleValidationService.validateRaffleForEntry(findRaffle);

        raffleValidationService.validateUserEntryLimit(findRaffle, user.getId(), 1);


        raffleTicketService.createTickets(findRaffle, user.getId(),2);

        int count = raffleTicketRepo.findAll().size();

        assertEquals(2, count);
    }
}
