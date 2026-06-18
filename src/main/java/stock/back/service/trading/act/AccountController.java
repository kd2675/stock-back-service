package stock.back.service.trading.act;

import auth.common.core.context.RequirePrincipalRole;
import auth.common.core.context.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import stock.back.service.trading.biz.AccountService;
import stock.back.service.trading.vo.AccountResponse;
import web.common.core.response.base.dto.ResponseDataDTO;

@RestController
@RequestMapping("/api/stock/v1/accounts")
@RequirePrincipalRole
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @GetMapping("/me")
    public ResponseDataDTO<AccountResponse> getMyAccount(UserContext userContext) {
        return ResponseDataDTO.of(accountService.toResponse(accountService.getOrOpenAccount(userContext.getUserKey())));
    }
}
