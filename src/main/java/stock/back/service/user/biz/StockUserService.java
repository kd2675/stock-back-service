package stock.back.service.user.biz;

import auth.common.core.client.UserServiceClient;
import auth.common.core.context.UserContext;
import auth.common.core.dto.UserDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import stock.back.service.trading.biz.AccountService;
import stock.back.service.trading.vo.AccountResponse;
import stock.back.service.user.vo.StockUserProfileResponse;
import web.common.core.response.base.dto.ResponseDataDTO;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockUserService {

    private final UserServiceClient userServiceClient;
    private final AccountService accountService;

    public StockUserProfileResponse getMyProfile(UserContext userContext) {
        AccountResponse account = accountService.findAccount(userContext.getUserKey())
                .map(accountService::toResponse)
                .orElse(null);
        UserDto user = findUser(userContext);
        return new StockUserProfileResponse(
                user.getUserKey(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                account
        );
    }

    private UserDto findUser(UserContext userContext) {
        try {
            ResponseDataDTO<UserDto> response = userServiceClient.getUserByUserKey(userContext.getUserKey());
            if (response != null && response.getData() != null) {
                return response.getData();
            }
        } catch (RuntimeException ex) {
            log.warn("Auth user lookup failed for stock profile: userKey={}, reason={}", userContext.getUserKey(), ex.getMessage());
        }
        return UserDto.builder()
                .userKey(userContext.getUserKey())
                .username(userContext.getUserName())
                .role(userContext.getRole())
                .build();
    }
}
