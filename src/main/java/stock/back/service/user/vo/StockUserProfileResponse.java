package stock.back.service.user.vo;

import stock.back.service.trading.vo.AccountResponse;

public record StockUserProfileResponse(
        String userKey,
        String username,
        String email,
        String role,
        AccountResponse account
) {
}
