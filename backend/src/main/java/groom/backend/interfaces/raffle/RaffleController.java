package groom.backend.interfaces.raffle;

import groom.backend.application.raffle.RaffleApplicationService;
import groom.backend.domain.auth.entity.User;
import groom.backend.domain.raffle.criteria.RaffleSearchCriteria;
import groom.backend.interfaces.raffle.dto.mapper.RaffleSearchMapper;
import groom.backend.interfaces.raffle.dto.request.RaffleRequest;
import groom.backend.interfaces.raffle.dto.request.RaffleSearchRequest;
import groom.backend.interfaces.raffle.dto.response.RaffleResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/raffles")
public class RaffleController {

    private final RaffleApplicationService raffleApplicationService;

    @PostMapping
    public ResponseEntity<RaffleResponse> createRaffle(@AuthenticationPrincipal(expression = "user") User user , @RequestBody RaffleRequest raffleRequest) {
        if (user == null || user.getEmail() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        RaffleResponse response = raffleApplicationService.createRaffle(user, raffleRequest);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{raffleId}")
    public ResponseEntity<RaffleResponse> updateRaffle(@AuthenticationPrincipal(expression = "user") User user,
                                                       @PathVariable Long raffleId,
                                                       @RequestBody RaffleRequest raffleRequest) {
        if (user == null || user.getEmail() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        RaffleResponse response = raffleApplicationService.updateRaffle(user, raffleId, raffleRequest);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{raffleId}")
    public ResponseEntity<Void> deleteRaffle(@AuthenticationPrincipal(expression = "user") User user,
                                             @PathVariable Long raffleId) {
        if (user == null || user.getEmail() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        raffleApplicationService.deleteRaffle(user, raffleId);

        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<Page<RaffleResponse>> searchRaffles(
            @AuthenticationPrincipal(expression = "user") User user,
            @ModelAttribute RaffleSearchRequest cond,
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        if (user == null || user.getEmail() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

// 인터페이스 계층에서 DTO -> 도메인 기준으로 변환
        RaffleSearchCriteria criteria = RaffleSearchMapper.toCriteria(cond);
        Page<RaffleResponse> page = raffleApplicationService.searchRaffles(criteria, pageable);
        return ResponseEntity.ok(page);
    }

    @GetMapping("/{raffleId}")
    public ResponseEntity<RaffleResponse> getRaffleDetails(@AuthenticationPrincipal(expression = "user") User user,
                                                           @PathVariable Long raffleId) {
        if (user == null || user.getEmail() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        RaffleResponse response = raffleApplicationService.getRaffleDetails(raffleId);
        return ResponseEntity.ok(response);
    }
}
