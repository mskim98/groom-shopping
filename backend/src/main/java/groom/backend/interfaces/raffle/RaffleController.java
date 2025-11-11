package groom.backend.interfaces.raffle;

import groom.backend.application.raffle.RaffleApplicationService;
import groom.backend.application.raffle.RaffleDrawApplicationService;
import groom.backend.application.raffle.RaffleTicketApplicationService;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.auth.entity.User;
import groom.backend.domain.raffle.criteria.RaffleSearchCriteria;
import groom.backend.interfaces.raffle.dto.mapper.RaffleSearchMapper;
import groom.backend.interfaces.raffle.dto.request.RaffleEntryRequest;
import groom.backend.interfaces.raffle.dto.request.RaffleRequest;
import groom.backend.interfaces.raffle.dto.request.RaffleSearchRequest;
import groom.backend.interfaces.raffle.dto.request.RaffleUpdateRequest;
import groom.backend.interfaces.raffle.dto.response.RaffleResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
@RequestMapping("/v1/raffles")
@Tag(name = "Raffle", description = "추첨 관련 API")
@SecurityRequirement(name = "JWT")
public class RaffleController {

    private final RaffleApplicationService raffleApplicationService;
    private final RaffleTicketApplicationService raffleTicketApplicationService;
    private final RaffleDrawApplicationService raffleDrawApplicationService;

    @Operation(
            summary = "추첨 생성",
            description = "새로운 추첨을 생성합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "추첨 생성 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = RaffleResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다.")
    })
    @PostMapping
    public ResponseEntity<RaffleResponse> createRaffle(
            @Parameter(hidden = true) @AuthenticationPrincipal(expression = "user") User user,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "추첨 생성 요청",
                    required = true,
                    content = @Content(schema = @Schema(implementation = RaffleRequest.class))
            )
            @RequestBody @Valid RaffleRequest raffleRequest) {
        if (user == null || user.getEmail() == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        RaffleResponse response = raffleApplicationService.createRaffle(user, raffleRequest);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "추첨 수정",
            description = "기존 추첨의 정보를 수정합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "추첨 수정 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = RaffleResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다."),
            @ApiResponse(responseCode = "404", description = "추첨을 찾을 수 없음")
    })
    @PutMapping("/{raffleId}")
    public ResponseEntity<RaffleResponse> updateRaffle(
            @Parameter(hidden = true) @AuthenticationPrincipal(expression = "user") User user,
            @Parameter(description = "추첨 ID", required = true, example = "1")
            @PathVariable Long raffleId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "추첨 수정 요청",
                    required = true,
                    content = @Content(schema = @Schema(implementation = RaffleUpdateRequest.class))
            )
            @RequestBody @Valid RaffleUpdateRequest raffleRequest) {
        if (user == null || user.getEmail() == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        RaffleResponse response = raffleApplicationService.updateRaffle(user, raffleId, raffleRequest);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "추첨 삭제",
            description = "추첨을 삭제합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "추첨 삭제 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다."),
            @ApiResponse(responseCode = "404", description = "추첨을 찾을 수 없음")
    })
    @DeleteMapping("/{raffleId}")
    public ResponseEntity<Void> deleteRaffle(
            @Parameter(hidden = true) @AuthenticationPrincipal(expression = "user") User user,
            @Parameter(description = "추첨 ID", required = true, example = "1")
            @PathVariable Long raffleId) {
        if (user == null || user.getEmail() == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        raffleApplicationService.deleteRaffle(user, raffleId);

        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "추첨 검색",
            description = "조건에 맞는 추첨 목록을 검색합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "추첨 검색 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = RaffleResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다.")
    })
    @GetMapping
    public ResponseEntity<Page<RaffleResponse>> searchRaffles(
            @Parameter(hidden = true) @AuthenticationPrincipal(expression = "user") User user,
            @Parameter(description = "검색 조건")
            @ModelAttribute RaffleSearchRequest cond,
            @Parameter(description = "페이징 정보", example = "page=0&size=10&sort=createdAt,DESC")
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        if (user == null || user.getEmail() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

// 인터페이스 계층에서 DTO -> 도메인 기준으로 변환
        RaffleSearchCriteria criteria = RaffleSearchMapper.toCriteria(cond);
        Page<RaffleResponse> page = raffleApplicationService.searchRaffles(criteria, pageable);
        return ResponseEntity.ok(page);
    }

    @Operation(
            summary = "추첨 상세 조회",
            description = "추첨 ID로 추첨 상세 정보를 조회합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "추첨 조회 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = RaffleResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다."),
            @ApiResponse(responseCode = "404", description = "추첨을 찾을 수 없음")
    })
    @GetMapping("/{raffleId}")
    public ResponseEntity<RaffleResponse> getRaffleDetails(
            @Parameter(hidden = true) @AuthenticationPrincipal(expression = "user") User user,
            @Parameter(description = "추첨 ID", required = true, example = "1")
            @PathVariable Long raffleId) {
        if (user == null || user.getEmail() == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        RaffleResponse response = raffleApplicationService.getRaffleDetails(raffleId);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "추첨 참여",
            description = "추첨에 참여합니다. 추첨 티켓을 발급받습니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "추첨 참여 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다."),
            @ApiResponse(responseCode = "404", description = "추첨을 찾을 수 없음")
    })
    @PostMapping("/{raffleId}/entries")
    public ResponseEntity<Void> addToEntryCart(
            @Parameter(hidden = true) @AuthenticationPrincipal(expression = "user") User user,
            @Parameter(description = "추첨 ID", required = true, example = "1")
            @PathVariable Long raffleId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "추첨 참여 요청",
                    required = true,
                    content = @Content(schema = @Schema(implementation = RaffleEntryRequest.class))
            )
            @RequestBody @Valid RaffleEntryRequest entry) {
        if (user == null || user.getEmail() == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        raffleTicketApplicationService.addToEntryCart(raffleId, user.getId(), entry.getCount());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @Operation(
            summary = "추첨 실행",
            description = "추첨을 실행하여 당첨자를 선정합니다. 당첨자에게 알림을 전송합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "추첨 실행 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다."),
            @ApiResponse(responseCode = "404", description = "추첨을 찾을 수 없음")
    })
    @PostMapping("/{raffleId}/draws")
    public ResponseEntity<Void> drawRaffleWinners(
            @Parameter(hidden = true) @AuthenticationPrincipal(expression = "user") User user,
            @Parameter(description = "추첨 ID", required = true, example = "1")
            @PathVariable Long raffleId) {
        if (user == null || user.getEmail() == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        // 추첨 실행
        raffleDrawApplicationService.drawRaffleWinners(user, raffleId);
        // 당첨자 알림 전송
        raffleDrawApplicationService.sendRaffleWinnersNotification(raffleId);

        return ResponseEntity.ok().build();
    }
}
