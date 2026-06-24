package stock.back.service.user.biz;

import auth.common.core.client.UserServiceClient;
import auth.common.core.context.UserContext;
import auth.common.core.dto.UserDto;
import org.junit.jupiter.api.Test;
import stock.back.service.database.entity.StockAccount;
import stock.back.service.trading.biz.AccountService;
import stock.back.service.trading.vo.AccountResponse;
import web.common.core.response.base.dto.ResponseDataDTO;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class StockUserServiceTest {

    private final UserServiceClient userServiceClient = mock(UserServiceClient.class);
    private final AccountService accountService = mock(AccountService.class);
    private final StockUserService stockUserService = new StockUserService(userServiceClient, accountService);

    @Test
    void getMyProfile_authUserExists_returnsAuthUserAndAccount() {
        UserContext context = context();
        StockAccount account = StockAccount.open("user-1");
        account.depositCash(new BigDecimal("10000000"));
        UserDto user = UserDto.builder()
                .userKey("user-1")
                .username("harry")
                .email("harry@example.com")
                .role("USER")
                .build();
        when(accountService.findAccount("user-1")).thenReturn(Optional.of(account));
        when(accountService.toResponse(account)).thenReturn(accountResponse());
        when(userServiceClient.getUserByUserKey("user-1")).thenReturn(ResponseDataDTO.of(user));

        var response = stockUserService.getMyProfile(context);

        assertThat(response.userKey()).isEqualTo("user-1");
        assertThat(response.username()).isEqualTo("harry");
        assertThat(response.email()).isEqualTo("harry@example.com");
        assertThat(response.account().cashBalance()).isEqualByComparingTo(new BigDecimal("10000000"));
        verify(accountService).findAccount("user-1");
        verify(accountService).toResponse(account);
        verifyNoMoreInteractions(accountService);
    }

    @Test
    void getMyProfile_authLookupFails_returnsUserContextFallback() {
        UserContext context = context();
        StockAccount account = StockAccount.open("user-1");
        account.depositCash(new BigDecimal("10000000"));
        when(accountService.findAccount("user-1")).thenReturn(Optional.of(account));
        when(accountService.toResponse(account)).thenReturn(accountResponse());
        when(userServiceClient.getUserByUserKey("user-1")).thenThrow(new IllegalStateException("auth unavailable"));

        var response = stockUserService.getMyProfile(context);

        assertThat(response.userKey()).isEqualTo("user-1");
        assertThat(response.username()).isEqualTo("harry");
        assertThat(response.email()).isNull();
        assertThat(response.role()).isEqualTo("USER");
    }

    @Test
    void getMyProfile_noStockAccount_returnsProfileWithoutOpeningAccount() {
        UserContext context = context();
        when(accountService.findAccount("user-1")).thenReturn(Optional.empty());
        when(userServiceClient.getUserByUserKey("user-1")).thenThrow(new IllegalStateException("auth unavailable"));

        var response = stockUserService.getMyProfile(context);

        assertThat(response.userKey()).isEqualTo("user-1");
        assertThat(response.account()).isNull();
        verify(accountService).findAccount("user-1");
        verifyNoMoreInteractions(accountService);
    }

    private UserContext context() {
        return UserContext.builder()
                .userKey("user-1")
                .userName("harry")
                .role("USER")
                .build();
    }

    private AccountResponse accountResponse() {
        return new AccountResponse(
                1L,
                "user-1",
                "STK-TEST000001",
                "ACTIVE",
                new BigDecimal("10000000"),
                null,
                null,
                null,
                null,
                null
        );
    }
}
