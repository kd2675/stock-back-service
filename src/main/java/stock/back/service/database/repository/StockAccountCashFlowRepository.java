package stock.back.service.database.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import stock.back.service.database.entity.StockAccountCashFlow;

import java.math.BigDecimal;

public interface StockAccountCashFlowRepository extends JpaRepository<StockAccountCashFlow, Long> {

    @Query(
            value = """
                    select coalesce(sum(
                        case flow_type
                            when 'DEPOSIT' then amount
                            when 'WITHDRAW' then -amount
                            else 0
                        end
                    ), 0)
                    from stock_account_cash_flow
                    where account_id = :accountId
                    """,
            nativeQuery = true
    )
    BigDecimal sumNetCashFlowByAccountId(@Param("accountId") Long accountId);
}
