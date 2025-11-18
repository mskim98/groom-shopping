package groom.backend.interfaces.raffle;

import groom.backend.application.raffle.RaffleApplicationService;
import groom.backend.application.raffle.RaffleDrawApplicationService;
import groom.backend.application.raffle.RaffleTicketApplicationService;
import groom.backend.common.annotation.CheckPermission;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.auth.entity.User;
import groom.backend.domain.raffle.criteria.RaffleSearchCriteria;
import groom.backend.interfaces.raffle.dto.mapper.RaffleSearchMapper;
import groom.backend.interfaces.raffle.dto.request.*;
import groom.backend.interfaces.raffle.dto.response.*;
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
            description = "ADMIN 권한을 가진 사용자가 새로운 추첨을 생성합니다.",
            security = { @SecurityRequirement(name = "JWT", scopes = {"ROLE_ADMIN"}) }
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "추첨 생성 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = RaffleResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다.")
    })
    @CheckPermission(roles = {"ADMIN"}, mode = CheckPermission.Mode.ANY, page = CheckPermission.Page.FO)
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
            description = "ADMIN 권한을 가진 사용자가 기존 추첨의 정보를 수정합니다.",
            security = { @SecurityRequirement(name = "JWT", scopes = {"ROLE_ADMIN"}) }
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "추첨 수정 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = RaffleResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다."),
            @ApiResponse(responseCode = "404", description = "추첨을 찾을 수 없음")
    })
    @CheckPermission(roles = {"ADMIN"}, mode = CheckPermission.Mode.ANY, page = CheckPermission.Page.BO)
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
            description = "ADMIN 권한 가진 사용자가 추첨을 삭제합니다.",
            security = { @SecurityRequirement(name = "JWT", scopes = {"ROLE_ADMIN"}) }
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "추첨 삭제 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다."),
            @ApiResponse(responseCode = "404", description = "추첨을 찾을 수 없음")
    })
    @CheckPermission(roles = {"ADMIN"}, mode = CheckPermission.Mode.ANY, page = CheckPermission.Page.BO)
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
                            schema = @Schema(implementation = RaffleResponse.class)))
    })
    @GetMapping
    public ResponseEntity<Page<RaffleResponse>> searchRaffles(
            @Parameter(description = "검색 조건")
            @ModelAttribute RaffleSearchRequest cond,
            @Parameter(description = "페이징 정보", example = "page=0&size=10&sort=createdAt,DESC")
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

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
                            schema = @Schema(implementation = RaffleDetailResponse.class))),
            @ApiResponse(responseCode = "404", description = "추첨을 찾을 수 없음")
    })
    @GetMapping("/{raffleId}")
    public ResponseEntity<RaffleDetailResponse> getRaffleDetails(
            @Parameter(description = "추첨 ID", required = true, example = "1")
            @PathVariable Long raffleId) {

        RaffleDetailResponse response = raffleApplicationService.getRaffleDetails(raffleId);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "추첨 참여",
            description = "추첨에 참여합니다. 추첨 티켓을 발급받습니다.",
    security = { @SecurityRequirement(name = "JWT", scopes = {"ROLE_USER","ROLE_ADMIN"}) }
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "추첨 참여 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다."),
            @ApiResponse(responseCode = "404", description = "추첨을 찾을 수 없음")
    })
    @CheckPermission(roles = {"USER","ADMIN"}, mode = CheckPermission.Mode.ANY, page = CheckPermission.Page.FO)
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
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "추첨 실행",
            description = "ADMIN 권한을 가진 사용자가 추첨을 실행하여 당첨자를 선정합니다. 당첨자에게 알림을 전송합니다.",
            security = { @SecurityRequirement(name = "JWT", scopes = {"ROLE_ADMIN"}) }
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "추첨 실행 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다."),
            @ApiResponse(responseCode = "404", description = "추첨을 찾을 수 없음")
    })
    @CheckPermission(roles = {"ADMIN"}, mode = CheckPermission.Mode.ANY, page = CheckPermission.Page.BO)
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

        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "추첨 상태 변경",
            description = "ADMIN 권한을 가진 사용자가 추첨 상태를 변경 할 수 있다",
            security = { @SecurityRequirement(name = "JWT", scopes = {"ROLE_ADMIN"}) }
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "추첨 상태 변경 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다."),
            @ApiResponse(responseCode = "404", description = "추첨을 찾을 수 없음")
    })
    @CheckPermission(roles = {"ADMIN"}, mode = CheckPermission.Mode.ANY, page = CheckPermission.Page.BO)
    @PatchMapping("/{raffleId}/status")
    public ResponseEntity<RaffleResponse> updateRaffleStatus(
            @Parameter(hidden = true) @AuthenticationPrincipal(expression = "user") User user,
            @Parameter(description = "추첨 ID", required = true, example = "1")
            @PathVariable Long raffleId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "추첨 상태 업데이트 요청",
                    required = true,
                    content = @Content(schema = @Schema(implementation = RaffleStatusUpdateRequest.class))
            )
            @RequestBody @Valid RaffleStatusUpdateRequest raffleRequest) {
        if (user == null || user.getEmail() == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        RaffleResponse response = raffleApplicationService.updateRaffleStatus(user, raffleId, raffleRequest);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "응모자 검색",
            description = "해당 추첨에 응모한 사람들 조회."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "응모자 검색 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ParticipantResponse.class)))
    })
    @CheckPermission(roles = {"ADMIN"}, mode = CheckPermission.Mode.ANY, page = CheckPermission.Page.BO)
    @GetMapping("/{raffleId}/participants")
    public ResponseEntity<Page<ParticipantResponse>> searchParticipants(
            @Parameter(description = "검색 조건")
            @RequestParam(value = "keyword", required = false, defaultValue = "") String keyword,
            @PathVariable Long raffleId,
            @Parameter(description = "페이징 정보", example = "page=0&size=20&sort=createdAt,DESC")
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        // 인터페이스 계층에서 DTO -> 도메인 기준으로 변환
        Page<ParticipantResponse> page = raffleTicketApplicationService.searchParticipants(raffleId, keyword, pageable);
        return ResponseEntity.ok(page);
    }

    @Operation(
            summary = "당첨자 검색",
            description = "해당 추첨에 당첨된 사용자 목록 조회."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "당첨자 조회 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = WinnersListResponse.class)))
    })
    @CheckPermission(roles = {"ADMIN", "USER"}, mode = CheckPermission.Mode.ANY, page = CheckPermission.Page.BO)
    @GetMapping("/{raffleId}/winners")
    public ResponseEntity<WinnersListResponse> getWinners(@PathVariable Long raffleId) {
        WinnersListResponse res = raffleDrawApplicationService.getWinners(raffleId);
        return ResponseEntity.ok(res);
    }

    @Operation(
            summary = "나의 추첨 응모",
            description = "내가 응모한 항목들을 불러온다",
            security = { @SecurityRequirement(name = "JWT", scopes = {"ROLE_USER"}) }
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "나의 응모 목록 조회 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = MyRaffleEntryResponse.class)))
    })
    @CheckPermission(roles = {"ADMIN","USER"}, mode = CheckPermission.Mode.ANY, page = CheckPermission.Page.BO)
    @GetMapping("/my/entries")
    public ResponseEntity<Page<MyRaffleEntryResponse>> getMyEntries(@AuthenticationPrincipal(expression = "user") User user,
                                                                    @PageableDefault(size = 20) Pageable pageable) {
        if (user == null || user.getEmail() == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        Page<MyRaffleEntryResponse> res = raffleApplicationService.getMyEntries(user.getId(), pageable);
        return ResponseEntity.ok(res);
    }

}
