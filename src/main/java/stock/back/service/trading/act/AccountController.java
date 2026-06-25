package stock.back.service.trading.act;

import auth.common.core.constant.UserRole;
import auth.common.core.context.RequirePrincipalRole;
import auth.common.core.context.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import stock.back.service.trading.biz.AccountService;
import stock.back.service.trading.vo.AccountCashAdjustmentRequest;
import stock.back.service.trading.vo.AccountCashAdjustmentResponse;
import stock.back.service.trading.vo.AccountReconnectRequest;
import stock.back.service.trading.vo.AccountResponse;
import stock.back.service.trading.vo.AccountStatusResponse;
import web.common.core.response.base.dto.ResponseDataDTO;

@RestController
@RequestMapping("/api/stock/v1/accounts")
@RequirePrincipalRole
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @GetMapping("/me")
    public ResponseDataDTO<AccountResponse> getMyAccount(UserContext userContext) {
        return ResponseDataDTO.of(accountService.toResponse(accountService.requireAccount(userContext.getUserKey())));
    }

    @GetMapping("/me/status")
    public ResponseDataDTO<AccountStatusResponse> getMyAccountStatus(UserContext userContext) {
        return ResponseDataDTO.of(accountService.findAccount(userContext.getUserKey())
                .map(account -> AccountStatusResponse.connected(accountService.toResponse(account)))
                .orElseGet(AccountStatusResponse::missing));
    }

    @PostMapping("/me")
    public ResponseDataDTO<AccountResponse> openMyAccount(UserContext userContext) {
        return ResponseDataDTO.of(accountService.openAccount(userContext.getUserKey()));
    }

    @DeleteMapping("/me")
    public ResponseDataDTO<AccountResponse> detachMyAccount(UserContext userContext) {
        return ResponseDataDTO.of(accountService.detachAccount(userContext.getUserKey()));
    }

    @PostMapping("/reconnect")
    public ResponseDataDTO<AccountResponse> reconnectMyAccount(
            UserContext userContext,
            @RequestBody AccountReconnectRequest request
    ) {
        return ResponseDataDTO.of(accountService.reconnectAccount(userContext.getUserKey(), request));
    }

    @PostMapping("/admin/users/{userKey}/cash-adjustments")
    @RequirePrincipalRole(anyOf = {UserRole.ADMIN})
    public ResponseDataDTO<AccountCashAdjustmentResponse> adjustUserAccountCash(
            @PathVariable String userKey,
            @RequestBody AccountCashAdjustmentRequest request,
            UserContext userContext
    ) {
        return ResponseDataDTO.of(accountService.adjustUserAccountCash(userKey, request, userContext.getUserKey()));
    }
}
