package stock.back.service.user.act;

import auth.common.core.context.RequirePrincipalRole;
import auth.common.core.context.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import stock.back.service.user.biz.StockUserService;
import stock.back.service.user.vo.StockUserProfileResponse;
import web.common.core.response.base.dto.ResponseDataDTO;

@RestController
@RequestMapping("/api/stock/v1/users")
@RequirePrincipalRole
@RequiredArgsConstructor
public class StockUserController {

    private final StockUserService stockUserService;

    @GetMapping("/me")
    public ResponseDataDTO<StockUserProfileResponse> getMyProfile(UserContext userContext) {
        return ResponseDataDTO.of(stockUserService.getMyProfile(userContext));
    }
}
