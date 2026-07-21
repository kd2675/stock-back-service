package stock.back.service.market.biz;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.market.vo.AdminFundFlowScope;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminFlowQueryServiceTest {

    private static final LocalDateTime SIMULATION_DAY_START = LocalDateTime.of(2026, 7, 3, 0, 0);
    private static final LocalDateTime SIMULATION_NOW = LocalDateTime.of(2026, 7, 3, 10, 0);

    @Test
    void getAdminFundFlowSummary_recentSimulationDay_readsScopedAggregateWithJdbcClient() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate("admin_flow_query_service_fund_summary_test");
        AdminFlowQueryService service = createService(jdbcTemplate);
        seedFundFlow(jdbcTemplate);
        insertCashFlowAt(jdbcTemplate, 5L, 1L, "DEPOSIT", "1000.00", "ADMIN_DEPOSIT", SIMULATION_DAY_START.minusMinutes(1));
        insertExecutionAt(jdbcTemplate, 5L, 1L, "SELL", "1000.00", "1.00", "2.00", "1000.00", SIMULATION_DAY_START.minusMinutes(1));

        var summary = service.getAdminFundFlowSummary();

        assertThat(summary.activeAccountCount()).isEqualTo(2L);
        assertThat(summary.totalCashBalance()).isEqualByComparingTo(new BigDecimal("2880.00"));
        assertThat(summary.totalReservedBuyCash()).isEqualByComparingTo(new BigDecimal("270.00"));
        assertThat(summary.totalHoldingMarketValue()).isEqualByComparingTo(new BigDecimal("260.00"));
        assertThat(summary.totalHoldingQuantity()).isEqualTo(3L);
        assertThat(summary.totalReservedSellQuantity()).isEqualTo(1L);
        assertThat(summary.totalAvailableHoldingQuantity()).isEqualTo(2L);
        assertThat(summary.holdingPositionCount()).isEqualTo(2L);
        assertThat(summary.totalAsset()).isEqualByComparingTo(new BigDecimal("3410.00"));
        assertThat(summary.externalDepositAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(summary.externalWithdrawAmount()).isEqualByComparingTo(new BigDecimal("120.00"));
        assertThat(summary.netExternalCashFlow()).isEqualByComparingTo(new BigDecimal("380.00"));
        assertThat(summary.dividendIncomeAmount()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(summary.buyNetAmount()).isEqualByComparingTo(new BigDecimal("700.00"));
        assertThat(summary.sellNetAmount()).isEqualByComparingTo(new BigDecimal("900.00"));
        assertThat(summary.tradeNetCashFlow()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(summary.totalFeeAmount()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(summary.totalTaxAmount()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(summary.realizedProfit()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(summary.executionCount()).isEqualTo(2L);
    }

    @Test
    void getAdminFundFlowSummary_all_readsFullAggregateWithJdbcClient() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate("admin_flow_query_service_fund_summary_all_test");
        AdminFlowQueryService service = createService(jdbcTemplate);
        seedFundFlow(jdbcTemplate);
        insertCashFlowAt(jdbcTemplate, 5L, 1L, "DEPOSIT", "1000.00", "ADMIN_DEPOSIT", SIMULATION_DAY_START.minusMinutes(1));
        insertExecutionAt(jdbcTemplate, 5L, 1L, "SELL", "1000.00", "1.00", "2.00", "1000.00", SIMULATION_DAY_START.minusMinutes(1));

        var summary = service.getAdminFundFlowSummary(AdminFundFlowScope.ALL);

        assertThat(summary.netExternalCashFlow()).isEqualByComparingTo(new BigDecimal("1380.00"));
        assertThat(summary.sellNetAmount()).isEqualByComparingTo(new BigDecimal("1900.00"));
        assertThat(summary.tradeNetCashFlow()).isEqualByComparingTo(new BigDecimal("1200.00"));
        assertThat(summary.totalFeeAmount()).isEqualByComparingTo(new BigDecimal("11.00"));
        assertThat(summary.totalTaxAmount()).isEqualByComparingTo(new BigDecimal("7.00"));
        assertThat(summary.realizedProfit()).isEqualByComparingTo(new BigDecimal("1200.00"));
        assertThat(summary.executionCount()).isEqualTo(3L);
    }

    @Test
    void getAdminSymbolFlows_recentSimulationDay_excludesOlderExecutions() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate("admin_flow_query_service_symbol_flow_recent_test");
        AdminFlowQueryService service = createService(jdbcTemplate);
        seedSymbolFlow(jdbcTemplate);
        insertSymbolExecutionAt(jdbcTemplate, 1L, "STOCK001", "BUY", 3L, "300.00", "290.00", SIMULATION_DAY_START.minusMinutes(1));
        insertSymbolExecutionAt(jdbcTemplate, 2L, "STOCK001", "SELL", 3L, "300.00", "295.00", SIMULATION_DAY_START.minusMinutes(1));
        insertSymbolExecutionAt(jdbcTemplate, 3L, "STOCK001", "BUY", 2L, "200.00", "190.00", SIMULATION_NOW.minusMinutes(10));
        insertSymbolExecutionAt(jdbcTemplate, 4L, "STOCK001", "SELL", 2L, "200.00", "195.00", SIMULATION_NOW.minusMinutes(10));

        var response = service.getAdminSymbolFlows(0);

        assertThat(response.totalCount()).isEqualTo(1L);
        assertThat(response.symbolFlows()).hasSize(1);
        var symbolFlow = response.symbolFlows().getFirst();
        assertThat(symbolFlow.symbol()).isEqualTo("STOCK001");
        assertThat(symbolFlow.executionCount()).isEqualTo(1L);
        assertThat(symbolFlow.executionQuantity()).isEqualTo(2L);
        assertThat(symbolFlow.turnoverAmount()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(symbolFlow.sellQuantity()).isEqualTo(2L);
        assertThat(symbolFlow.buyQuantity()).isEqualTo(2L);
        assertThat(symbolFlow.lastExecutedAt()).isEqualTo(SIMULATION_NOW.minusMinutes(10));
    }

    @Test
    void getAdminSymbolFlows_all_includesOlderExecutionsForCumulativeView() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate("admin_flow_query_service_symbol_flow_all_test");
        AdminFlowQueryService service = createService(jdbcTemplate);
        seedSymbolFlow(jdbcTemplate);
        insertSymbolExecutionAt(jdbcTemplate, 1L, "STOCK001", "BUY", 3L, "300.00", "290.00", SIMULATION_DAY_START.minusMinutes(1));
        insertSymbolExecutionAt(jdbcTemplate, 2L, "STOCK001", "SELL", 3L, "300.00", "295.00", SIMULATION_DAY_START.minusMinutes(1));
        insertSymbolExecutionAt(jdbcTemplate, 3L, "STOCK001", "BUY", 2L, "200.00", "190.00", SIMULATION_NOW.minusMinutes(10));
        insertSymbolExecutionAt(jdbcTemplate, 4L, "STOCK001", "SELL", 2L, "200.00", "195.00", SIMULATION_NOW.minusMinutes(10));
        insertDailySnapshot(
                jdbcTemplate,
                100L,
                "STOCK001",
                SIMULATION_DAY_START.minusDays(1).toLocalDate(),
                "95.00",
                "100.00",
                1L,
                3L,
                "300.00",
                3L,
                3L
        );

        var response = service.getAdminSymbolFlows(0, AdminFundFlowScope.ALL);

        assertThat(response.totalCount()).isEqualTo(1L);
        assertThat(response.symbolFlows()).hasSize(1);
        var symbolFlow = response.symbolFlows().getFirst();
        assertThat(symbolFlow.executionCount()).isEqualTo(2L);
        assertThat(symbolFlow.executionQuantity()).isEqualTo(5L);
        assertThat(symbolFlow.turnoverAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(symbolFlow.buyQuantity()).isEqualTo(5L);
        assertThat(symbolFlow.sellQuantity()).isEqualTo(5L);
    }

    @Test
    void getAdminSymbolFlows_withDailyCumulative_returnsRecentSimulationDays() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate("admin_flow_query_service_symbol_flow_daily_cumulative_test");
        AdminFlowQueryService service = createService(jdbcTemplate);
        seedSymbolFlow(jdbcTemplate);
        insertSymbolExecutionAt(jdbcTemplate, 1L, "STOCK001", "BUY", 3L, "300.00", "290.00", SIMULATION_NOW.minusMinutes(10));
        insertSymbolExecutionAt(jdbcTemplate, 2L, "STOCK001", "SELL", 2L, "200.00", "195.00", SIMULATION_DAY_START.minusDays(1).plusHours(1));
        insertSymbolExecutionAt(jdbcTemplate, 3L, "STOCK001", "BUY", 9L, "900.00", "890.00", SIMULATION_DAY_START.minusDays(8).plusHours(1));
        insertDailySnapshot(
                jdbcTemplate,
                100L,
                "STOCK001",
                SIMULATION_DAY_START.minusDays(1).toLocalDate(),
                "95.00",
                "100.00",
                7L,
                12L,
                "1200.00",
                5L,
                7L
        );

        var response = service.getAdminSymbolFlows(0, AdminFundFlowScope.ALL, true, 7);

        assertThat(response.dailyCumulativeFlows()).hasSize(7);
        var today = response.dailyCumulativeFlows().get(0);
        assertThat(today.simulationTradeDate()).isEqualTo(SIMULATION_DAY_START.toLocalDate());
        assertThat(today.symbolFlows()).hasSize(1);
        assertThat(today.symbolFlows().getFirst().executionCount()).isEqualTo(1L);
        assertThat(today.symbolFlows().getFirst().buyQuantity()).isEqualTo(3L);
        assertThat(today.symbolFlows().getFirst().currentPrice()).isNull();
        assertThat(today.symbolFlows().getFirst().previousClose()).isNull();
        assertThat(today.symbolFlows().getFirst().changeRate()).isNull();
        var yesterday = response.dailyCumulativeFlows().get(1);
        assertThat(yesterday.simulationTradeDate()).isEqualTo(SIMULATION_DAY_START.minusDays(1).toLocalDate());
        assertThat(yesterday.symbolFlows().getFirst().executionCount()).isEqualTo(7L);
        assertThat(yesterday.symbolFlows().getFirst().executionQuantity()).isEqualTo(12L);
        assertThat(yesterday.symbolFlows().getFirst().turnoverAmount()).isEqualByComparingTo(new BigDecimal("1200.00"));
        assertThat(yesterday.symbolFlows().getFirst().buyQuantity()).isEqualTo(5L);
        assertThat(yesterday.symbolFlows().getFirst().sellQuantity()).isEqualTo(7L);
        assertThat(yesterday.symbolFlows().getFirst().currentPrice()).isEqualByComparingTo(new BigDecimal("95.00"));
        assertThat(yesterday.symbolFlows().getFirst().previousClose()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(yesterday.symbolFlows().getFirst().changeRate()).isEqualByComparingTo(new BigDecimal("-5.0000"));
        assertThat(response.dailyCumulativeFlows().get(6).symbolFlows().getFirst().executionCount()).isZero();

        var olderResponse = service.getAdminSymbolFlows(0, AdminFundFlowScope.ALL, true, 7, 7);

        assertThat(olderResponse.dailyCumulativeFlows()).hasSize(7);
        assertThat(olderResponse.dailyCumulativeFlows().get(0).simulationTradeDate()).isEqualTo(SIMULATION_DAY_START.minusDays(7).toLocalDate());
        assertThat(olderResponse.dailyCumulativeFlows().get(1).simulationTradeDate()).isEqualTo(SIMULATION_DAY_START.minusDays(8).toLocalDate());
        assertThat(olderResponse.dailyCumulativeFlows().get(1).symbolFlows().getFirst().executionCount()).isEqualTo(1L);
        assertThat(olderResponse.dailyCumulativeFlows().get(1).symbolFlows().getFirst().buyQuantity()).isEqualTo(9L);
        assertThat(olderResponse.dailyCumulativeFlows().get(1).symbolFlows().getFirst().currentPrice()).isNull();
        assertThat(olderResponse.dailyCumulativeFlows().get(1).symbolFlows().getFirst().changeRate()).isNull();
    }

    @Test
    void getAdminCashFlows_readsPageWithJdbcClient() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate("admin_flow_query_service_cash_page_test");
        AdminFlowQueryService service = createService(jdbcTemplate);
        insertAccount(jdbcTemplate, 1L, "flow-user-1", "ACTIVE", "1000.00");
        insertAccount(jdbcTemplate, 2L, "flow-user-2", "ACTIVE", "2000.00");
        insertCashFlow(jdbcTemplate, 1L, 1L, "DEPOSIT", "100.00", "ADMIN_DEPOSIT", 0);
        insertCashFlow(jdbcTemplate, 2L, 2L, "WITHDRAW", "50.00", "ADMIN_WITHDRAW", 1);
        insertCashFlow(jdbcTemplate, 3L, 1L, "DEPOSIT", "30.00", "DIVIDEND_PAYMENT", 2);

        var page = service.getAdminCashFlows(0, 2);

        assertThat(page.totalElements()).isEqualTo(3L);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.hasPrevious()).isFalse();
        assertThat(page.hasNext()).isTrue();
        assertThat(page.content()).hasSize(2);
        assertThat(page.content().get(0).id()).isEqualTo(1L);
        assertThat(page.content().get(0).userKey()).isEqualTo("flow-user-1");
        assertThat(page.content().get(1).id()).isEqualTo(2L);
        assertThat(page.content().get(1).userKey()).isEqualTo("flow-user-2");
    }

    @Test
    void getAdminTotalAssetHistory_readsSevenSettlementDaysWithChanges() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate("admin_flow_query_service_total_asset_history_test");
        AdminFlowQueryService service = createService(jdbcTemplate);
        insertAccount(jdbcTemplate, 1L, "history-active-user", "ACTIVE", "0.00");
        insertAccount(jdbcTemplate, 2L, "history-closed-user", "CLOSED", "0.00");
        insertAccount(jdbcTemplate, 3L, "stock-listing-HISTORY", "ACTIVE", "0.00");
        for (int dayOffset = 0; dayOffset < 10; dayOffset++) {
            LocalDate snapshotDate = SIMULATION_DAY_START.toLocalDate().minusDays(dayOffset);
            insertPortfolioSnapshot(jdbcTemplate, dayOffset * 2L + 1L, 1L, snapshotDate, 1000 - dayOffset * 10L);
            insertPortfolioSnapshot(jdbcTemplate, dayOffset * 2L + 2L, 2L, snapshotDate, 2000 - dayOffset * 20L);
        }
        insertPortfolioSnapshot(jdbcTemplate, 100L, 3L, SIMULATION_DAY_START.toLocalDate(), 999999L);

        var firstPage = service.getAdminTotalAssetHistory(0);

        assertThat(firstPage.content()).hasSize(7);
        assertThat(firstPage.totalElements()).isEqualTo(10L);
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(firstPage.hasPrevious()).isFalse();
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(firstPage.content().getFirst().snapshotDate()).isEqualTo(LocalDate.of(2026, 7, 3));
        assertThat(firstPage.content().getFirst().totalAsset()).isEqualByComparingTo("3000.00");
        assertThat(firstPage.content().getFirst().pendingSubscriptionAsset()).isZero();
        assertThat(firstPage.content().getFirst().holdingQuantity()).isEqualTo(30L);
        assertThat(firstPage.content().getFirst().reservedSellQuantity()).isEqualTo(5L);
        assertThat(firstPage.content().getFirst().availableHoldingQuantity()).isEqualTo(25L);
        assertThat(firstPage.content().getFirst().holdingPositionCount()).isEqualTo(3L);
        assertThat(firstPage.content().getFirst().changeAmount()).isEqualByComparingTo("30.00");
        assertThat(firstPage.content().getFirst().changeRate()).isEqualByComparingTo("1.0101");
        assertThat(firstPage.summary().rangeStart()).isEqualTo(LocalDate.of(2026, 6, 27));
        assertThat(firstPage.summary().rangeEnd()).isEqualTo(LocalDate.of(2026, 7, 3));
        assertThat(firstPage.summary().changeAmount()).isEqualByComparingTo("180.00");
        assertThat(firstPage.summary().changeRate()).isEqualByComparingTo("6.3830");
        assertThat(firstPage.summary().averageTotalAsset()).isEqualByComparingTo("2910.00");

        var secondPage = service.getAdminTotalAssetHistory(1);

        assertThat(secondPage.content()).hasSize(3);
        assertThat(secondPage.hasPrevious()).isTrue();
        assertThat(secondPage.hasNext()).isFalse();
        assertThat(secondPage.content().getLast().snapshotDate()).isEqualTo(LocalDate.of(2026, 6, 24));
        assertThat(secondPage.content().getLast().changeAmount()).isNull();
    }

    @Test
    void getAdminTotalAssetHistory_whenAnyDailyHoldingMetricIsMissing_returnsUnknownHoldingMetrics() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate("admin_flow_query_service_total_asset_history_partial_metrics_test");
        AdminFlowQueryService service = createService(jdbcTemplate);
        LocalDate snapshotDate = SIMULATION_DAY_START.toLocalDate();
        insertAccount(jdbcTemplate, 1L, "history-user-1", "ACTIVE", "0.00");
        insertAccount(jdbcTemplate, 2L, "history-user-2", "ACTIVE", "0.00");
        insertPortfolioSnapshot(jdbcTemplate, 1L, 1L, snapshotDate, 1000L);
        jdbcTemplate.update(
                """
                insert into portfolio_snapshot(
                    id, account_id, snapshot_date, total_asset, cash_balance, market_value,
                    holding_quantity, reserved_sell_quantity, holding_position_count, return_rate, created_at
                )
                values (2, 2, ?, 2000, 1000, 1000, null, null, null, 0, ?)
                """,
                snapshotDate,
                snapshotDate.atTime(18, 0)
        );

        var point = service.getAdminTotalAssetHistory(0).content().getFirst();

        assertThat(point.holdingQuantity()).isNull();
        assertThat(point.reservedSellQuantity()).isNull();
        assertThat(point.availableHoldingQuantity()).isNull();
        assertThat(point.holdingPositionCount()).isNull();
    }

    @Test
    void getAdminTotalAssetHistory_partialSettlement_doesNotExposeUncommittedCycleDate() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate("admin_flow_query_service_total_asset_partial_cycle_test");
        AdminFlowQueryService service = createService(jdbcTemplate);
        LocalDate completedDate = SIMULATION_DAY_START.toLocalDate().minusDays(1);
        LocalDate partialDate = SIMULATION_DAY_START.toLocalDate();
        insertAccount(jdbcTemplate, 1L, "history-user", "ACTIVE", "0.00");
        insertPortfolioSnapshot(jdbcTemplate, 1L, 1L, completedDate, 1000L);
        insertPostCloseCycle(jdbcTemplate, 10L, "LEDGER_FROZEN");
        insertCyclePortfolioSnapshot(jdbcTemplate, 2L, 10L, 100L, 1L, partialDate, 2000L);

        var page = service.getAdminTotalAssetHistory(0);

        assertThat(page.content()).extracting(point -> point.snapshotDate()).containsExactly(completedDate);
    }

    @Test
    void getAdminTotalAssetHistory_settledCycle_exposesCompletedCycleDate() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate("admin_flow_query_service_total_asset_settled_cycle_test");
        AdminFlowQueryService service = createService(jdbcTemplate);
        LocalDate snapshotDate = SIMULATION_DAY_START.toLocalDate();
        insertAccount(jdbcTemplate, 1L, "history-user", "ACTIVE", "0.00");
        insertPostCloseCycle(jdbcTemplate, 10L, "PORTFOLIO_SETTLED");
        insertCyclePortfolioSnapshot(jdbcTemplate, 1L, 10L, 100L, 1L, snapshotDate, 2000L);

        var page = service.getAdminTotalAssetHistory(0);

        assertThat(page.content()).extracting(point -> point.snapshotDate()).containsExactly(snapshotDate);
    }

    @Test
    void getAdminTotalAssetHistory_settledSnapshot_exposesStoredCashAndSubscriptionAsset() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate("admin_flow_query_service_total_asset_components_test");
        AdminFlowQueryService service = createService(jdbcTemplate);
        LocalDate snapshotDate = SIMULATION_DAY_START.toLocalDate();
        insertAccount(jdbcTemplate, 1L, "history-user", "ACTIVE", "0.00");
        insertPostCloseCycle(jdbcTemplate, 10L, "PORTFOLIO_SETTLED");
        insertCyclePortfolioSnapshot(jdbcTemplate, 1L, 10L, 100L, 1L, snapshotDate, 1000L);
        jdbcTemplate.update(
                """
                update portfolio_snapshot
                   set cash_balance = 750,
                       pending_subscription_asset = 50,
                       market_value = 200
                 where id = 1
                """
        );

        var point = service.getAdminTotalAssetHistory(0).content().getFirst();

        assertThat(point.cashBalance()).isEqualByComparingTo("750.00");
        assertThat(point.pendingSubscriptionAsset()).isEqualByComparingTo("50.00");
    }

    @Test
    void getAdminFlowOverview_recentSimulationDay_countsUntilCurrentSimulationTime() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate("admin_flow_query_service_overview_recent_until_now_test");
        AdminFlowQueryService service = createService(jdbcTemplate);
        insertOrderAt(jdbcTemplate, 1L, 1L, "BUY", "FILLED", "0.00", SIMULATION_NOW.minusMinutes(10));
        insertOrderAt(jdbcTemplate, 2L, 1L, "BUY", "CANCELLED", "0.00", SIMULATION_NOW.minusMinutes(5));
        insertOrderAt(jdbcTemplate, 3L, 1L, "BUY", "REJECTED", "0.00", SIMULATION_NOW.plusMinutes(5));
        insertCorporateAction(jdbcTemplate, 1L, "STOCK001", "ANNOUNCED", SIMULATION_NOW.minusMinutes(10));
        insertCorporateAction(jdbcTemplate, 2L, "STOCK001", "LISTED", SIMULATION_NOW.minusMinutes(5));
        insertCorporateAction(jdbcTemplate, 3L, "STOCK001", "DELISTED", SIMULATION_NOW.plusMinutes(5));

        var overview = service.getAdminFlowOverview(0, false, false);

        assertThat(overview.orderFlow().todayOrderCount()).isEqualTo(2L);
        assertThat(overview.orderFlow().todayFilledOrderCount()).isEqualTo(1L);
        assertThat(overview.orderFlow().todayCancelledOrderCount()).isEqualTo(1L);
        assertThat(overview.orderFlow().todayRejectedOrderCount()).isZero();
        assertThat(overview.corporateActionFlow().todayCreatedCount()).isEqualTo(2L);
        assertThat(overview.generatedAt()).isEqualTo(SIMULATION_NOW);
    }

    @Test
    void getAdminFlowOverview_investorFlow_groupsCurrentDayByParticipantCategory() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate("admin_flow_query_service_investor_flow_test");
        AdminFlowQueryService service = createService(jdbcTemplate);
        insertAccount(jdbcTemplate, 1L, "manual-user", "ACTIVE", "1000.00");
        insertAccount(jdbcTemplate, 2L, "auto-user", "ACTIVE", "1000.00");
        insertAccount(jdbcTemplate, 3L, "stock-listing-STOCK001", "ACTIVE", "1000.00");
        jdbcTemplate.update("insert into stock_auto_participant(user_key) values ('auto-user')");
        insertExecutionDaySummary(jdbcTemplate, 1L, 100L, 20L, "10000.00", "2000.00", SIMULATION_NOW.minusSeconds(7));
        insertExecutionDaySummary(jdbcTemplate, 2L, 30L, 60L, "3000.00", "6000.00", SIMULATION_NOW.minusSeconds(5));
        insertExecutionDaySummary(jdbcTemplate, 3L, 10L, 40L, "1000.00", "4000.00", SIMULATION_NOW.minusSeconds(3));

        var investorFlow = service.getAdminFlowOverview(0, false, false).investorFlow();

        assertThat(investorFlow.simulationTradeDate()).isEqualTo(SIMULATION_DAY_START.toLocalDate());
        assertThat(investorFlow.totalBuyQuantity()).isEqualTo(140L);
        assertThat(investorFlow.totalSellQuantity()).isEqualTo(120L);
        assertThat(investorFlow.totalParticipationQuantity()).isEqualTo(260L);
        assertThat(investorFlow.sourceUpdatedAt()).isEqualTo(SIMULATION_NOW.minusSeconds(3));
        assertThat(investorFlow.categories())
                .extracting("category", "buyQuantity", "sellQuantity", "netQuantity", "participationQuantity")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("MANUAL_PARTICIPANT", 100L, 20L, 80L, 120L),
                        org.assertj.core.groups.Tuple.tuple("AUTO_PARTICIPANT", 30L, 60L, -30L, 90L),
                        org.assertj.core.groups.Tuple.tuple("LISTING_UNDERWRITER", 10L, 40L, -30L, 50L)
                );
        assertThat(investorFlow.categories().getFirst().buyShareRate()).isEqualByComparingTo("71.4286");
        assertThat(investorFlow.categories().getFirst().executionShareRate()).isEqualByComparingTo("46.1538");
        assertThat(investorFlow.categories().get(1).netCashFlow()).isEqualByComparingTo("2997.00");
    }

    @Test
    void getAdminFlowOverview_investorFlow_withoutExecutions_returnsAllCategoriesWithZeroRates() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate("admin_flow_query_service_empty_investor_flow_test");
        AdminFlowQueryService service = createService(jdbcTemplate);

        var investorFlow = service.getAdminFlowOverview(0, false, false).investorFlow();

        assertThat(investorFlow.totalParticipationQuantity()).isZero();
        assertThat(investorFlow.sourceUpdatedAt()).isNull();
        assertThat(investorFlow.categories())
                .extracting("category", "executionShareRate")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("MANUAL_PARTICIPANT", new BigDecimal("0.0000")),
                        org.assertj.core.groups.Tuple.tuple("AUTO_PARTICIPANT", new BigDecimal("0.0000")),
                        org.assertj.core.groups.Tuple.tuple("LISTING_UNDERWRITER", new BigDecimal("0.0000"))
                );
    }

    private AdminFlowQueryService createService(JdbcTemplate jdbcTemplate) {
        StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository = mock(StockOrderBookInstrumentRepository.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        when(simulationClockService.currentMarketDayStart()).thenReturn(SIMULATION_DAY_START);
        when(simulationClockService.currentMarketDateTime()).thenReturn(SIMULATION_NOW);
        return new AdminFlowQueryService(
                jdbcTemplate,
                new AdminSymbolFlowQueryService(jdbcTemplate, stockOrderBookInstrumentRepository, simulationClockService),
                simulationClockService
        );
    }

    private JdbcTemplate createJdbcTemplate(String databaseName) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                ""
        ));
        jdbcTemplate.execute("""
                create table stock_account (
                    id bigint primary key,
                    user_key varchar(100) not null,
                    status varchar(30) not null,
                    cash_balance decimal(19, 2) not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_auto_participant (
                    user_key varchar(100) primary key
                )
                """);
        jdbcTemplate.execute("""
                create table stock_order (
                    id bigint primary key,
                    account_id bigint not null,
                    symbol varchar(20) not null default 'STOCK001',
                    market_type varchar(30) not null,
                    side varchar(10) not null,
                    status varchar(30) not null,
                    reserved_cash decimal(19, 2) not null,
                    quantity bigint not null,
                    filled_quantity bigint not null,
                    created_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_holding (
                    account_id bigint not null,
                    symbol varchar(20) not null,
                    quantity bigint not null,
                    reserved_quantity bigint not null default 0,
                    average_price decimal(19, 2) not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_price (
                    symbol varchar(20) primary key,
                    current_price decimal(19, 2) not null,
                    previous_close decimal(19, 2) not null default 0
                )
                """);
        jdbcTemplate.execute("""
                create table stock_order_book_daily_snapshot (
                    id bigint primary key,
                    close_run_id bigint not null,
                    symbol varchar(20) not null,
                    simulation_trade_date date not null,
                    snapshot_at timestamp not null,
                    name varchar(120) not null,
                    market varchar(20) not null,
                    enabled boolean not null,
                    market_enabled boolean not null,
                    market_status varchar(20) not null,
                    issued_shares bigint not null,
                    tradable_shares bigint not null,
                    initial_price decimal(19, 2) not null,
                    tick_size decimal(19, 2) not null,
                    price_limit_rate decimal(5, 2) not null,
                    close_price decimal(19, 2) not null,
                    previous_close decimal(19, 2) not null,
                    change_rate decimal(9, 4) not null default 0,
                    price_time timestamp,
                    price_provider varchar(40),
                    execution_count bigint not null default 0,
                    execution_quantity bigint not null default 0,
                    turnover_amount decimal(19, 2) not null default 0,
                    buy_quantity bigint not null default 0,
                    sell_quantity bigint not null default 0,
                    buy_net_amount decimal(19, 2) not null default 0,
                    sell_net_amount decimal(19, 2) not null default 0,
                    open_order_count bigint not null default 0,
                    open_buy_order_count bigint not null default 0,
                    open_sell_order_count bigint not null default 0,
                    reserved_buy_cash decimal(19, 2) not null default 0,
                    holder_count bigint not null default 0,
                    holding_quantity bigint not null default 0,
                    pending_corporate_action_count bigint not null default 0,
                    last_executed_at timestamp,
                    created_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_market_close_run (
                    id bigint primary key,
                    symbol varchar(20),
                    business_date date not null,
                    status varchar(20) not null,
                    completed_at timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table stock_post_close_cycle (
                    id bigint primary key,
                    close_run_id bigint,
                    scope_type varchar(20) not null,
                    scope_key varchar(120) not null,
                    phase varchar(60) not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_order_book_instrument (
                    symbol varchar(20) primary key,
                    name varchar(100) not null,
                    enabled boolean not null,
                    issued_shares bigint not null,
                    tradable_shares bigint not null,
                    initial_price decimal(19, 2) not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_order_book_market_config (
                    symbol varchar(20) primary key,
                    market_status varchar(30) not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_corporate_action (
                    id bigint primary key,
                    symbol varchar(20) not null,
                    status varchar(30) not null,
                    created_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_account_cash_flow (
                    id bigint primary key,
                    account_id bigint not null,
                    flow_type varchar(30) not null,
                    amount decimal(19, 2) not null,
                    reason varchar(100) not null,
                    created_by varchar(100) not null,
                    created_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_corporate_action_entitlement (
                    account_id bigint not null,
                    subscribed_cash_amount decimal(19, 2),
                    status varchar(20) not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_execution (
                    id bigint primary key,
                    account_id bigint not null,
                    symbol varchar(20) not null,
                    side varchar(10) not null,
                    source varchar(40) not null default 'INTERNAL_ORDER_BOOK',
                    quantity bigint not null default 1,
                    gross_amount decimal(19, 2) not null default 0,
                    net_amount decimal(19, 2) not null,
                    fee_amount decimal(19, 2) not null,
                    tax_amount decimal(19, 2) not null,
                    realized_profit decimal(19, 2) not null,
                    executed_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_execution_account_day_summary (
                    simulation_trade_date date not null,
                    account_id bigint not null,
                    execution_count bigint not null default 0,
                    buy_quantity bigint not null default 0,
                    sell_quantity bigint not null default 0,
                    gross_amount decimal(19, 2) not null default 0,
                    buy_gross_amount decimal(19, 2) not null default 0,
                    sell_gross_amount decimal(19, 2) not null default 0,
                    buy_net_amount decimal(19, 2) not null default 0,
                    sell_net_amount decimal(19, 2) not null default 0,
                    fee_amount decimal(19, 2) not null default 0,
                    tax_amount decimal(19, 2) not null default 0,
                    realized_profit decimal(19, 2) not null default 0,
                    last_executed_at timestamp,
                    updated_at timestamp not null,
                    primary key (simulation_trade_date, account_id)
                )
                """);
        jdbcTemplate.execute("""
                create table portfolio_snapshot (
                    id bigint primary key,
                    close_cycle_id bigint,
                    close_run_id bigint,
                    account_id bigint not null,
                    snapshot_date date not null,
                    total_asset decimal(19, 2) not null,
                    cash_balance decimal(19, 2) not null,
                    pending_subscription_asset decimal(19, 2) not null default 0,
                    market_value decimal(19, 2) not null,
                    holding_quantity bigint,
                    reserved_sell_quantity bigint,
                    holding_position_count bigint,
                    return_rate decimal(9, 4) not null,
                    created_at timestamp not null,
                    unique (account_id, snapshot_date)
                )
                """);
        return jdbcTemplate;
    }

    private void seedFundFlow(JdbcTemplate jdbcTemplate) {
        insertAccount(jdbcTemplate, 1L, "active-user-1", "ACTIVE", "880.00");
        insertAccount(jdbcTemplate, 2L, "active-user-2", "ACTIVE", "2000.00");
        insertAccount(jdbcTemplate, 3L, "closed-user", "CLOSED", "9999.00");
        insertAccount(jdbcTemplate, 4L, "stock-listing-STOCK001", "ACTIVE", "999999.00");
        insertOrder(jdbcTemplate, 1L, 1L, "BUY", "PENDING", "100.00");
        insertOrder(jdbcTemplate, 2L, 2L, "BUY", "PARTIALLY_FILLED", "50.00");
        insertOrder(jdbcTemplate, 3L, 1L, "SELL", "PENDING", "0.00");
        insertOrder(jdbcTemplate, 4L, 1L, "BUY", "FILLED", "999.00");
        insertOrder(jdbcTemplate, 5L, 4L, "BUY", "PENDING", "999999.00");
        insertPrice(jdbcTemplate, "STOCK001", "80.00");
        insertHolding(jdbcTemplate, 1L, "STOCK001", 2L, 1L, "70.00");
        insertHolding(jdbcTemplate, 2L, "STOCK002", 1L, 0L, "100.00");
        insertHolding(jdbcTemplate, 3L, "STOCK001", 50L, 10L, "70.00");
        insertHolding(jdbcTemplate, 4L, "STOCK001", 999L, 999L, "70.00");
        insertCashFlow(jdbcTemplate, 1L, 1L, "DEPOSIT", "500.00", "ADMIN_DEPOSIT", 3);
        insertCashFlow(jdbcTemplate, 2L, 1L, "WITHDRAW", "120.00", "ADMIN_WITHDRAW", 2);
        insertCashFlow(jdbcTemplate, 3L, 2L, "DEPOSIT", "30.00", "DIVIDEND_PAYMENT", 1);
        insertCashFlow(jdbcTemplate, 4L, 3L, "DEPOSIT", "9999.00", "ADMIN_DEPOSIT", 0);
        insertCashFlow(jdbcTemplate, 50L, 1L, "WITHDRAW", "120.00", "CAPITAL_INCREASE_SUBSCRIPTION", 1);
        jdbcTemplate.update(
                "insert into stock_corporate_action_entitlement(account_id, subscribed_cash_amount, status) values (1, 120.00, 'SUBSCRIBED')"
        );
        insertExecution(jdbcTemplate, 1L, 1L, "BUY", "700.00", "7.00", "0.00", "0.00");
        insertExecution(jdbcTemplate, 2L, 2L, "SELL", "900.00", "3.00", "5.00", "200.00");
        insertExecution(jdbcTemplate, 3L, 3L, "SELL", "9999.00", "1.00", "1.00", "9999.00");
    }

    private void seedSymbolFlow(JdbcTemplate jdbcTemplate) {
        insertAccount(jdbcTemplate, 1L, "symbol-user-1", "ACTIVE", "1000.00");
        insertAccount(jdbcTemplate, 2L, "symbol-user-2", "ACTIVE", "2000.00");
        jdbcTemplate.update(
                """
                insert into stock_order_book_instrument(
                    symbol, name, enabled, issued_shares, tradable_shares, initial_price
                )
                values ('STOCK001', '테스트주식', true, 100000, 90000, ?)
                """,
                new BigDecimal("100.00")
        );
        jdbcTemplate.update(
                "insert into stock_order_book_market_config(symbol, market_status) values ('STOCK001', 'REGULAR')"
        );
        jdbcTemplate.update(
                "insert into stock_price(symbol, current_price, previous_close) values ('STOCK001', ?, ?)",
                new BigDecimal("110.00"),
                new BigDecimal("100.00")
        );
    }

    private void insertAccount(JdbcTemplate jdbcTemplate, long id, String userKey, String status, String cashBalance) {
        jdbcTemplate.update(
                "insert into stock_account(id, user_key, status, cash_balance) values (?, ?, ?, ?)",
                id,
                userKey,
                status,
                new BigDecimal(cashBalance)
        );
    }

    private void insertExecutionDaySummary(
            JdbcTemplate jdbcTemplate,
            long accountId,
            long buyQuantity,
            long sellQuantity,
            String buyAmount,
            String sellAmount,
            LocalDateTime updatedAt
    ) {
        BigDecimal buyGrossAmount = new BigDecimal(buyAmount);
        BigDecimal sellGrossAmount = new BigDecimal(sellAmount);
        jdbcTemplate.update(
                """
                insert into stock_execution_account_day_summary(
                    simulation_trade_date, account_id, execution_count,
                    buy_quantity, sell_quantity, gross_amount,
                    buy_gross_amount, sell_gross_amount,
                    buy_net_amount, sell_net_amount,
                    fee_amount, tax_amount, realized_profit,
                    last_executed_at, updated_at
                )
                values (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, ?, ?)
                """,
                SIMULATION_DAY_START.toLocalDate(),
                accountId,
                buyQuantity,
                sellQuantity,
                buyGrossAmount.add(sellGrossAmount),
                buyGrossAmount,
                sellGrossAmount,
                buyGrossAmount.add(BigDecimal.ONE),
                sellGrossAmount.subtract(BigDecimal.valueOf(2)),
                updatedAt,
                updatedAt
        );
    }

    private void insertPortfolioSnapshot(
            JdbcTemplate jdbcTemplate,
            long id,
            long accountId,
            LocalDate snapshotDate,
            long totalAsset
    ) {
        BigDecimal totalAssetAmount = BigDecimal.valueOf(totalAsset).setScale(2);
        BigDecimal cashBalance = totalAssetAmount.divide(BigDecimal.valueOf(2));
        jdbcTemplate.update(
                """
                insert into portfolio_snapshot(
                    id, account_id, snapshot_date, total_asset, cash_balance, market_value,
                    holding_quantity, reserved_sell_quantity, holding_position_count, return_rate, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """,
                id,
                accountId,
                snapshotDate,
                totalAssetAmount,
                cashBalance,
                totalAssetAmount.subtract(cashBalance),
                accountId == 1L ? 10L : 20L,
                accountId == 1L ? 2L : 3L,
                accountId == 1L ? 1L : 2L,
                snapshotDate.atTime(18, 0)
        );
    }

    private void insertPostCloseCycle(JdbcTemplate jdbcTemplate, long cycleId, String phase) {
        jdbcTemplate.update(
                "insert into stock_post_close_cycle(id, scope_type, scope_key, phase) values (?, 'FULL_MARKET', 'ALL', ?)",
                cycleId,
                phase
        );
    }

    private void insertCyclePortfolioSnapshot(
            JdbcTemplate jdbcTemplate,
            long id,
            long closeCycleId,
            long closeRunId,
            long accountId,
            LocalDate snapshotDate,
            long totalAsset
    ) {
        BigDecimal totalAssetAmount = BigDecimal.valueOf(totalAsset).setScale(2);
        BigDecimal cashBalance = totalAssetAmount.divide(BigDecimal.valueOf(2));
        jdbcTemplate.update(
                """
                insert into portfolio_snapshot(
                    id, close_cycle_id, close_run_id, account_id, snapshot_date,
                    total_asset, cash_balance, market_value,
                    holding_quantity, reserved_sell_quantity, holding_position_count,
                    return_rate, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, 10, 2, 1, 0, ?)
                """,
                id,
                closeCycleId,
                closeRunId,
                accountId,
                snapshotDate,
                totalAssetAmount,
                cashBalance,
                totalAssetAmount.subtract(cashBalance),
                snapshotDate.atTime(18, 0)
        );
    }

    private void insertOrder(JdbcTemplate jdbcTemplate, long id, long accountId, String side, String status, String reservedCash) {
        insertOrderAt(jdbcTemplate, id, accountId, side, status, reservedCash, LocalDateTime.now());
    }

    private void insertOrderAt(
            JdbcTemplate jdbcTemplate,
            long id,
            long accountId,
            String side,
            String status,
            String reservedCash,
            LocalDateTime createdAt
    ) {
        jdbcTemplate.update(
                """
                insert into stock_order(
                    id, account_id, market_type, side, status, reserved_cash, quantity, filled_quantity, created_at
                )
                values (?, ?, 'ORDER_BOOK', ?, ?, ?, 1, 0, ?)
                """,
                id,
                accountId,
                side,
                status,
                new BigDecimal(reservedCash),
                createdAt
        );
    }

    private void insertCorporateAction(
            JdbcTemplate jdbcTemplate,
            long id,
            String symbol,
            String status,
            LocalDateTime createdAt
    ) {
        jdbcTemplate.update(
                """
                insert into stock_corporate_action(id, symbol, status, created_at)
                values (?, ?, ?, ?)
                """,
                id,
                symbol,
                status,
                createdAt
        );
    }

    private void insertPrice(JdbcTemplate jdbcTemplate, String symbol, String currentPrice) {
        jdbcTemplate.update(
                "insert into stock_price(symbol, current_price) values (?, ?)",
                symbol,
                new BigDecimal(currentPrice)
        );
    }

    private void insertHolding(
            JdbcTemplate jdbcTemplate,
            long accountId,
            String symbol,
            long quantity,
            long reservedQuantity,
            String averagePrice
    ) {
        jdbcTemplate.update(
                "insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price) values (?, ?, ?, ?, ?)",
                accountId,
                symbol,
                quantity,
                reservedQuantity,
                new BigDecimal(averagePrice)
        );
    }

    private void insertCashFlow(
            JdbcTemplate jdbcTemplate,
            long id,
            long accountId,
            String flowType,
            String amount,
            String reason,
            long minute
    ) {
        jdbcTemplate.update(
                """
                insert into stock_account_cash_flow(
                    id, account_id, flow_type, amount, reason, created_by, created_at
                )
                values (?, ?, ?, ?, ?, 'test-admin', ?)
                """,
                id,
                accountId,
                flowType,
                new BigDecimal(amount),
                reason,
                SIMULATION_NOW.minusMinutes(minute)
        );
    }

    private void insertCashFlowAt(
            JdbcTemplate jdbcTemplate,
            long id,
            long accountId,
            String flowType,
            String amount,
            String reason,
            LocalDateTime createdAt
    ) {
        jdbcTemplate.update(
                """
                insert into stock_account_cash_flow(
                    id, account_id, flow_type, amount, reason, created_by, created_at
                )
                values (?, ?, ?, ?, ?, 'test-admin', ?)
                """,
                id,
                accountId,
                flowType,
                new BigDecimal(amount),
                reason,
                createdAt
        );
    }

    private void insertExecution(
            JdbcTemplate jdbcTemplate,
            long id,
            long accountId,
            String side,
            String netAmount,
            String feeAmount,
            String taxAmount,
            String realizedProfit
    ) {
        LocalDateTime executedAt = SIMULATION_NOW.minusMinutes(id);
        jdbcTemplate.update(
                """
                insert into stock_execution(
                    id, account_id, symbol, side, net_amount, fee_amount, tax_amount, realized_profit, executed_at
                )
                values (?, ?, 'STOCK001', ?, ?, ?, ?, ?, ?)
                """,
                id,
                accountId,
                side,
                new BigDecimal(netAmount),
                new BigDecimal(feeAmount),
                new BigDecimal(taxAmount),
                new BigDecimal(realizedProfit),
                executedAt
        );
        upsertExecutionDaySummary(
                jdbcTemplate,
                accountId,
                side,
                BigDecimal.ZERO,
                new BigDecimal(netAmount),
                new BigDecimal(feeAmount),
                new BigDecimal(taxAmount),
                new BigDecimal(realizedProfit),
                executedAt
        );
    }

    private void insertExecutionAt(
            JdbcTemplate jdbcTemplate,
            long id,
            long accountId,
            String side,
            String netAmount,
            String feeAmount,
            String taxAmount,
            String realizedProfit,
            LocalDateTime executedAt
    ) {
        jdbcTemplate.update(
                """
                insert into stock_execution(
                    id, account_id, symbol, side, net_amount, fee_amount, tax_amount, realized_profit, executed_at
                )
                values (?, ?, 'STOCK001', ?, ?, ?, ?, ?, ?)
                """,
                id,
                accountId,
                side,
                new BigDecimal(netAmount),
                new BigDecimal(feeAmount),
                new BigDecimal(taxAmount),
                new BigDecimal(realizedProfit),
                executedAt
        );
        upsertExecutionDaySummary(
                jdbcTemplate,
                accountId,
                side,
                BigDecimal.ZERO,
                new BigDecimal(netAmount),
                new BigDecimal(feeAmount),
                new BigDecimal(taxAmount),
                new BigDecimal(realizedProfit),
                executedAt
        );
    }

    private void upsertExecutionDaySummary(
            JdbcTemplate jdbcTemplate,
            long accountId,
            String side,
            BigDecimal grossAmount,
            BigDecimal netAmount,
            BigDecimal feeAmount,
            BigDecimal taxAmount,
            BigDecimal realizedProfit,
            LocalDateTime executedAt
    ) {
        boolean buy = "BUY".equals(side);
        BigDecimal buyGrossAmount = buy ? grossAmount : BigDecimal.ZERO;
        BigDecimal sellGrossAmount = buy ? BigDecimal.ZERO : grossAmount;
        BigDecimal buyNetAmount = buy ? netAmount : BigDecimal.ZERO;
        BigDecimal sellNetAmount = buy ? BigDecimal.ZERO : netAmount;
        int updated = jdbcTemplate.update(
                """
                update stock_execution_account_day_summary
                   set execution_count = execution_count + 1,
                       buy_quantity = buy_quantity + ?,
                       sell_quantity = sell_quantity + ?,
                       gross_amount = gross_amount + ?,
                       buy_gross_amount = buy_gross_amount + ?,
                       sell_gross_amount = sell_gross_amount + ?,
                       buy_net_amount = buy_net_amount + ?,
                       sell_net_amount = sell_net_amount + ?,
                       fee_amount = fee_amount + ?,
                       tax_amount = tax_amount + ?,
                       realized_profit = realized_profit + ?,
                       last_executed_at = case
                           when last_executed_at is null or last_executed_at < ? then ?
                           else last_executed_at
                       end,
                       updated_at = ?
                 where simulation_trade_date = ?
                   and account_id = ?
                """,
                buy ? 1L : 0L,
                buy ? 0L : 1L,
                grossAmount,
                buyGrossAmount,
                sellGrossAmount,
                buyNetAmount,
                sellNetAmount,
                feeAmount,
                taxAmount,
                realizedProfit,
                executedAt,
                executedAt,
                executedAt,
                executedAt.toLocalDate(),
                accountId
        );
        if (updated > 0) {
            return;
        }
        jdbcTemplate.update(
                """
                insert into stock_execution_account_day_summary(
                    simulation_trade_date, account_id, execution_count,
                    buy_quantity, sell_quantity, gross_amount,
                    buy_gross_amount, sell_gross_amount,
                    buy_net_amount, sell_net_amount,
                    fee_amount, tax_amount, realized_profit,
                    last_executed_at, updated_at
                )
                values (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                executedAt.toLocalDate(),
                accountId,
                buy ? 1L : 0L,
                buy ? 0L : 1L,
                grossAmount,
                buyGrossAmount,
                sellGrossAmount,
                buyNetAmount,
                sellNetAmount,
                feeAmount,
                taxAmount,
                realizedProfit,
                executedAt,
                executedAt
        );
    }

    private void insertSymbolExecutionAt(
            JdbcTemplate jdbcTemplate,
            long id,
            String symbol,
            String side,
            long quantity,
            String grossAmount,
            String netAmount,
            LocalDateTime executedAt
    ) {
        jdbcTemplate.update(
                """
                insert into stock_execution(
                    id, account_id, symbol, side, source, quantity, gross_amount, net_amount,
                    fee_amount, tax_amount, realized_profit, executed_at
                )
                values (?, 1, ?, ?, 'INTERNAL_ORDER_BOOK', ?, ?, ?, 0, 0, 0, ?)
                """,
                id,
                symbol,
                side,
                quantity,
                new BigDecimal(grossAmount),
                new BigDecimal(netAmount),
                executedAt
        );
    }

    private void insertDailySnapshot(
            JdbcTemplate jdbcTemplate,
            long closeRunId,
            String symbol,
            LocalDate simulationTradeDate,
            String closePrice,
            String previousClose,
            long executionCount,
            long executionQuantity,
            String turnoverAmount,
            long buyQuantity,
            long sellQuantity
    ) {
        jdbcTemplate.update(
                """
                insert into stock_market_close_run(id, symbol, business_date, status, completed_at)
                values (?, null, ?, 'COMPLETED', ?)
                """,
                closeRunId,
                simulationTradeDate,
                SIMULATION_NOW
        );
        jdbcTemplate.update(
                """
                insert into stock_post_close_cycle(id, close_run_id, scope_type, scope_key, phase)
                values (?, ?, 'FULL_MARKET', 'ALL', 'REPORTS_AGGREGATED')
                """,
                closeRunId,
                closeRunId
        );
        jdbcTemplate.update(
                """
                insert into stock_order_book_daily_snapshot(
                    id, close_run_id, symbol, simulation_trade_date, snapshot_at,
                    name, market, enabled, market_enabled, market_status,
                    issued_shares, tradable_shares, initial_price, tick_size, price_limit_rate,
                    close_price, previous_close, change_rate, price_time, price_provider,
                    execution_count, execution_quantity, turnover_amount,
                    buy_quantity, sell_quantity, buy_net_amount, sell_net_amount,
                    open_order_count, open_buy_order_count, open_sell_order_count, reserved_buy_cash,
                    holder_count, holding_quantity, pending_corporate_action_count,
                    last_executed_at, created_at
                )
                values (
                    ?, ?, ?, ?, ?,
                    '테스트주식', 'ORDER_BOOK', true, true, 'CLOSED',
                    100000, 90000, 100.00, 1.00, 30.00,
                    ?, ?, 0.0000, ?, 'SIMULATION',
                    ?, ?, ?,
                    ?, ?, 500.00, 700.00,
                    3, 1, 2, 1000.00,
                    2, 1500, 0,
                    ?, ?
                )
                """,
                closeRunId,
                closeRunId,
                symbol,
                simulationTradeDate,
                SIMULATION_NOW,
                new BigDecimal(closePrice),
                new BigDecimal(previousClose),
                SIMULATION_NOW,
                executionCount,
                executionQuantity,
                new BigDecimal(turnoverAmount),
                buyQuantity,
                sellQuantity,
                SIMULATION_NOW.minusMinutes(5),
                SIMULATION_NOW
        );
    }
}
