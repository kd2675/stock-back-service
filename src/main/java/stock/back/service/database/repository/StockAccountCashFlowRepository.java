package stock.back.service.database.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import stock.back.service.database.entity.StockAccountCashFlow;

import java.math.BigDecimal;
import java.util.List;

public interface StockAccountCashFlowRepository extends JpaRepository<StockAccountCashFlow, Long> {

    List<StockAccountCashFlow> findTop30ByAccountIdOrderByCreatedAtDescIdDesc(Long accountId);

    @Query(
            value = """
                    select coalesce(sum(
	                        case
	                            when flow_type = 'DEPOSIT' and reason <> 'DIVIDEND_PAYMENT' then amount
	                            when flow_type = 'WITHDRAW' then -amount
	                            else 0
	                        end
                    ), 0)
                    from stock_account_cash_flow
                    where account_id = :accountId
                    """,
            nativeQuery = true
    )
    BigDecimal sumNetCashFlowByAccountId(@Param("accountId") Long accountId);

    @Query(
            value = """
                    select
                      coalesce(sum(case when flow_type = 'DEPOSIT' and reason <> 'DIVIDEND_PAYMENT' then amount else 0 end), 0) as externalDepositAmount,
                      coalesce(sum(case when flow_type = 'WITHDRAW' then amount else 0 end), 0) as externalWithdrawAmount,
                      coalesce(sum(case when flow_type = 'DEPOSIT' and reason = 'DIVIDEND_PAYMENT' then amount else 0 end), 0) as dividendIncomeAmount
                    from stock_account_cash_flow
                    where account_id = :accountId
                    """,
            nativeQuery = true
    )
    CashFlowSummaryProjection summarizeCashFlowsByAccountId(@Param("accountId") Long accountId);

    interface CashFlowSummaryProjection {
        BigDecimal getExternalDepositAmount();

        BigDecimal getExternalWithdrawAmount();

        BigDecimal getDividendIncomeAmount();
    }
}
