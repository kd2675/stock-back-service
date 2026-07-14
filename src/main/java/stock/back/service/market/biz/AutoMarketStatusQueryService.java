package stock.back.service.market.biz;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.database.entity.AutoParticipantProfileType;
import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.database.entity.StockAutoMarketConfig;
import stock.back.service.database.entity.StockAutoParticipantProfileConfig;
import stock.back.service.database.repository.StockAutoMarketConfigRepository;
import stock.back.service.database.repository.StockAutoParticipantProfileConfigRepository;
import stock.back.service.database.repository.StockAutoParticipantRepository;
import stock.back.service.database.repository.StockExecutionMarketViewRepository;
import stock.back.service.database.repository.StockListingAutoAccountConfigRepository;
import stock.back.service.database.repository.StockOrderRepository;
import stock.back.service.market.vo.AutoMarketConfigResponse;
import stock.back.service.market.vo.AutoMarketDailyRegimeResponse;
import stock.back.service.market.vo.AutoMarketStatusResponse;
import stock.back.service.market.vo.AutoParticipantProfileConfigResponse;
import stock.back.service.market.vo.AutoParticipantResponse;
import stock.back.service.market.vo.AutoParticipantSymbolConfigResponse;
import stock.back.service.market.vo.ListingAutoAccountResponse;

@Service
@RequiredArgsConstructor
public class AutoMarketStatusQueryService {

    private final StockAutoMarketConfigRepository stockAutoMarketConfigRepository;
    private final StockAutoParticipantProfileConfigRepository stockAutoParticipantProfileConfigRepository;
    private final StockAutoParticipantRepository stockAutoParticipantRepository;
    private final StockListingAutoAccountConfigRepository stockListingAutoAccountConfigRepository;
    private final StockOrderRepository stockOrderRepository;
    private final StockExecutionMarketViewRepository stockExecutionMarketViewRepository;
    private final AutoMarketStatusDataLoader autoMarketStatusDataLoader;
    private final AutoMarketSummaryStatusQuery autoMarketSummaryStatusQuery;
    private final SimulationClockService simulationClockService;
    private final SimulationMarketSessionService simulationMarketSessionService;

    @Transactional(readOnly = true)
    public AutoMarketStatusResponse getAutoMarketStatus() {
        return getAutoMarketStatus(AutoMarketStatusQueryOptions.of(true, true, true, true, true, true, true, null));
    }

    @Transactional(readOnly = true)
    public AutoMarketStatusResponse getAutoMarketStatus(boolean includeParticipantSymbolConfigs) {
        return getAutoMarketStatus(AutoMarketStatusQueryOptions.of(
                true,
                true,
                includeParticipantSymbolConfigs,
                true,
                true,
                true,
                true,
                null
        ));
    }

    @Transactional(readOnly = true)
    public AutoMarketStatusResponse getAutoMarketStatus(
            boolean includeConfigs,
            boolean includeParticipants,
            boolean includeParticipantSymbolConfigs,
            boolean includeParticipantProfileConfigs,
            boolean includeListingAutoAccounts,
            boolean includeRuntimeMetrics
    ) {
        return getAutoMarketStatus(AutoMarketStatusQueryOptions.of(
                includeConfigs,
                includeParticipants,
                includeParticipantSymbolConfigs,
                includeParticipantProfileConfigs,
                includeListingAutoAccounts,
                includeRuntimeMetrics,
                true,
                null
        ));
    }

    @Transactional(readOnly = true)
    public AutoMarketStatusResponse getAutoMarketStatus(
            boolean includeConfigs,
            boolean includeParticipants,
            boolean includeParticipantSymbolConfigs,
            boolean includeParticipantProfileConfigs,
            boolean includeListingAutoAccounts,
            boolean includeRuntimeMetrics,
            boolean includeSalaryEligibility,
            String participantSymbolConfigUserKey
    ) {
        return getAutoMarketStatus(AutoMarketStatusQueryOptions.of(
                includeConfigs,
                includeParticipants,
                includeParticipantSymbolConfigs,
                includeParticipantProfileConfigs,
                includeListingAutoAccounts,
                includeRuntimeMetrics,
                includeSalaryEligibility,
                participantSymbolConfigUserKey
        ));
    }

    private AutoMarketStatusResponse getAutoMarketStatus(AutoMarketStatusQueryOptions options) {
        if (options.summaryOnly()) {
            return withEffectiveSession(autoMarketSummaryStatusQuery.getSummaryStatus(
                    options.includeRuntimeMetrics(),
                    options.includeSalaryEligibility()
            ));
        }
        List<StockAutoMarketConfig> configEntities = options.shouldLoadConfigs()
                ? stockAutoMarketConfigRepository.findAll().stream()
                        .sorted((left, right) -> left.getSymbol().compareTo(right.getSymbol()))
                        .toList()
                : List.of();
        LocalDateTime currentMarketDateTime = simulationClockService.currentMarketDateTime();
        Map<String, AutoMarketDailyRegimeResponse> dailyRegimesBySymbol = options.shouldLoadConfigs()
                ? autoMarketStatusDataLoader.loadDailyRegimesBySymbol(
	                        configEntities.stream().map(StockAutoMarketConfig::getSymbol).toList(),
	                        currentMarketDateTime.toLocalDate(),
	                        resolveRegimePhase(currentMarketDateTime),
	                        currentMarketDateTime
	                )
                : Map.of();
        List<AutoMarketConfigResponse> configs = options.shouldLoadConfigs()
                ? configEntities.stream()
                        .map(config -> AutoMarketStatusResponseMapper.toMarketConfig(config, dailyRegimesBySymbol.get(config.getSymbol())))
                        .toList()
                : List.of();
        List<AutoParticipantResponse> participants = options.shouldLoadParticipants()
                ? autoMarketStatusDataLoader.loadAutoParticipantStatusResponses()
                : List.of();
        List<AutoParticipantSymbolConfigTarget> participantSymbolConfigTargets = options.includeParticipantSymbolConfigs()
                ? autoMarketStatusDataLoader.resolveAutoParticipantSymbolConfigTargets(participants, options.participantSymbolConfigUserKey())
                : List.of();
        List<AutoParticipantSymbolConfigResponse> participantSymbolConfigs = options.includeParticipantSymbolConfigs()
                ? autoMarketStatusDataLoader.loadEffectiveAutoParticipantSymbolConfigs(participantSymbolConfigTargets, configEntities)
                : List.of();
        List<AutoParticipantProfileConfigResponse> participantProfileConfigs = options.includeParticipantProfileConfigs()
                ? getAutoParticipantProfileConfigs()
                : List.of();
        List<ListingAutoAccountResponse> listingAutoAccounts = options.includeListingAutoAccounts()
                ? autoMarketStatusDataLoader.toListingAutoAccountResponses(stockListingAutoAccountConfigRepository.findAllByOrderBySymbolAsc())
                : List.of();
        long configCount = options.shouldLoadConfigs() ? configEntities.size() : stockAutoMarketConfigRepository.count();
        long participantCount = options.shouldLoadParticipants() ? participants.size() : stockAutoParticipantRepository.countByWithdrawnAtIsNull();
        long participantProfileConfigCount = AutoParticipantProfileType.values().length;
        long listingAutoAccountCount = options.includeListingAutoAccounts() ? listingAutoAccounts.size() : stockListingAutoAccountConfigRepository.count();
        long enabledParticipantCount = options.shouldLoadParticipants()
                ? participants.stream().filter(AutoParticipantResponse::enabled).count()
                : stockAutoParticipantRepository.countByEnabledTrueAndWithdrawnAtIsNull();
        long salaryEligibleParticipantCount = options.includeSalaryEligibility()
                ? autoMarketSummaryStatusQuery.countSalaryEligibleAutoParticipants()
                : 0L;
        long enabledConfigCount = options.shouldLoadConfigs()
                ? configEntities.stream().filter(config -> Boolean.TRUE.equals(config.getEnabled())).count()
                : 0L;
        List<OrderStatus> openStatuses = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);
        long openAutoOrderCount = options.includeRuntimeMetrics() ? stockOrderRepository.countOpenAutoOrders(openStatuses, MarketType.ORDER_BOOK) : 0L;
        long todayAutoExecutionCount = options.includeRuntimeMetrics()
                ? stockExecutionMarketViewRepository.countAutoExecutionsBetween(
                        simulationClockService.currentMarketDayStart(),
                        currentMarketDateTime
                )
                : 0L;
        boolean enabled = simulationMarketSessionService.isRegularSession()
                && enabledParticipantCount > 0 && (options.shouldLoadConfigs()
                ? enabledConfigCount > 0
                : stockAutoMarketConfigRepository.existsByEnabledTrue());
        return AutoMarketStatusResponseMapper.toStatus(
                enabled,
                new AutoMarketStatusResponseMapper.AutoMarketStatusCounts(
                        configCount,
                        participantCount,
                        participantProfileConfigCount,
                        listingAutoAccountCount,
                        enabledParticipantCount,
                        salaryEligibleParticipantCount,
                        openAutoOrderCount,
                        todayAutoExecutionCount
                ),
                configs,
                participants,
                participantSymbolConfigs,
                participantProfileConfigs,
                listingAutoAccounts
        );
    }

    private AutoMarketStatusResponse withEffectiveSession(AutoMarketStatusResponse response) {
        if (simulationMarketSessionService.isRegularSession()) {
            return response;
        }
        return AutoMarketStatusResponseMapper.toStatus(
                false,
                new AutoMarketStatusResponseMapper.AutoMarketStatusCounts(
                        response.configCount(),
                        response.participantCount(),
                        response.participantProfileConfigCount(),
                        response.listingAutoAccountCount(),
                        response.enabledParticipantCount(),
                        response.salaryEligibleParticipantCount(),
                        response.openAutoOrderCount(),
                        response.todayAutoExecutionCount()
                ),
                response.configs(),
                response.participants(),
                response.participantSymbolConfigs(),
                response.participantProfileConfigs(),
                response.listingAutoAccounts()
        );
    }

    @Transactional(readOnly = true)
    public List<AutoParticipantProfileConfigResponse> getAutoParticipantProfileConfigs() {
        Map<AutoParticipantProfileType, StockAutoParticipantProfileConfig> savedConfigs = stockAutoParticipantProfileConfigRepository.findAllByOrderByProfileTypeAsc()
                .stream()
                .collect(Collectors.toMap(StockAutoParticipantProfileConfig::getProfileType, Function.identity()));
        return Arrays.stream(AutoParticipantProfileType.values())
                .map(profileType -> AutoParticipantProfileConfigResponseMapper.toResponse(profileType, savedConfigs.get(profileType)))
                .toList();
    }

    private String resolveRegimePhase(LocalDateTime currentMarketDateTime) {
        return AutoMarketRegimePhaseResolver.resolve(currentMarketDateTime);
    }

}
