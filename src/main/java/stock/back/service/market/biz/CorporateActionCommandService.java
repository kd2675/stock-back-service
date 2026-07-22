package stock.back.service.market.biz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockCapitalIncreaseOfferingType;
import stock.back.service.database.entity.StockCorporateAction;
import stock.back.service.database.entity.StockCorporateActionStatus;
import stock.back.service.database.entity.StockCorporateActionType;
import stock.back.service.database.entity.StockOrderBookInstrument;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.database.repository.StockCorporateActionRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.market.vo.CorporateActionRequest;
import stock.back.service.market.vo.OrderBookInstrumentResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class CorporateActionCommandService {

    private static final List<StockCorporateActionType> EXCLUSIVE_INSTRUMENT_ACTION_TYPES = List.of(
            StockCorporateActionType.PAID_IN_CAPITAL_INCREASE,
            StockCorporateActionType.STOCK_SPLIT,
            StockCorporateActionType.BONUS_ISSUE,
            StockCorporateActionType.STOCK_DIVIDEND,
            StockCorporateActionType.DELISTING
    );
    private static final List<StockCorporateActionStatus> ACTIVE_EXCLUSIVE_ACTION_STATUSES = List.of(
            StockCorporateActionStatus.ANNOUNCED,
            StockCorporateActionStatus.EX_RIGHTS_APPLIED,
            StockCorporateActionStatus.PAID
    );

    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final StockCorporateActionRepository stockCorporateActionRepository;
    private final StockPriceRepository stockPriceRepository;
    private final JdbcClient jdbcClient;
    private final SimulationClockService simulationClockService;

    public CorporateActionCommandService(
            StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository,
            StockCorporateActionRepository stockCorporateActionRepository,
            StockPriceRepository stockPriceRepository,
            JdbcTemplate jdbcTemplate,
            SimulationClockService simulationClockService
    ) {
        this.stockOrderBookInstrumentRepository = stockOrderBookInstrumentRepository;
        this.stockCorporateActionRepository = stockCorporateActionRepository;
        this.stockPriceRepository = stockPriceRepository;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.simulationClockService = simulationClockService;
    }

    @Transactional
    public OrderBookInstrumentResponse applyCorporateAction(String symbol, CorporateActionRequest request) {
        String normalizedSymbol = CorporateActionPolicy.requireSymbol(symbol);
        if (request == null || request.actionType() == null) {
            throw StockException.badRequest("Corporate action type is required");
        }
        if (request.actionType() == StockCorporateActionType.INITIAL_ISSUE) {
            throw StockException.badRequest("Initial issue is only allowed when creating an instrument");
        }
        CorporateActionFieldScopeValidator.validate(request);
        StockOrderBookInstrument instrument = stockOrderBookInstrumentRepository.findByIdForUpdate(normalizedSymbol)
                .orElseThrow(() -> StockException.notFound("Unknown order book symbol: " + normalizedSymbol));
        if (!Boolean.TRUE.equals(instrument.getEnabled())) {
            throw StockException.conflict("Corporate action is not allowed for a disabled or delisted instrument: " + normalizedSymbol);
        }
        LocalDateTime createdAt = simulationClockService.currentMarketDateTime();
        LocalDate currentSimulationDate = createdAt.toLocalDate();
        assertNoActiveExclusiveInstrumentAction(normalizedSymbol, request.actionType());

        return switch (request.actionType()) {
            case PAID_IN_CAPITAL_INCREASE -> announcePaidInCapitalIncrease(instrument, request, createdAt, currentSimulationDate);
            case BONUS_ISSUE, STOCK_DIVIDEND -> applyFreeShareDistribution(instrument, request, createdAt, currentSimulationDate);
            case STOCK_SPLIT -> applyStockSplit(instrument, request, createdAt, currentSimulationDate);
            case CASH_DIVIDEND -> applyCashDividend(instrument, request, createdAt, currentSimulationDate);
            case DELISTING -> applyDelisting(instrument, request, createdAt, currentSimulationDate);
            case INITIAL_ISSUE -> throw StockException.badRequest("Initial issue is only allowed when creating an instrument");
        };
    }

    private OrderBookInstrumentResponse announcePaidInCapitalIncrease(
            StockOrderBookInstrument instrument,
            CorporateActionRequest request,
            LocalDateTime createdAt,
            LocalDate currentSimulationDate
    ) {
        long shares = CorporateActionPolicy.requirePositiveShareQuantity(request.shareQuantity());
        BigDecimal issuePrice = CorporateActionPolicy.requirePositiveIssuePrice(request.issuePrice());
        LocalDate exRightsDate = request.exRightsDate();
        StockCapitalIncreaseOfferingType offeringType = request.offeringType() == null
                ? StockCapitalIncreaseOfferingType.defaultType()
                : request.offeringType();
        LocalDate recordDate = request.recordDate() == null
                ? CorporateActionPolicy.defaultPaidInRecordDate(offeringType, exRightsDate)
                : request.recordDate();
        LocalDate subscriptionStartDate = request.subscriptionStartDate() == null
                ? CorporateActionPolicy.defaultPaidInSubscriptionStartDate(
                        offeringType,
                        recordDate,
                        currentSimulationDate
                )
                : request.subscriptionStartDate();
        LocalDate subscriptionEndDate = request.subscriptionEndDate() == null
                ? CorporateActionPolicy.defaultPaidInSubscriptionEndDate(request.paymentDate())
                : request.subscriptionEndDate();
        LocalDate paymentDate = request.paymentDate();
        LocalDate listingDate = request.listingDate();
        CorporateActionPolicy.requirePaidInCapitalIncreaseDates(
                offeringType,
                exRightsDate,
                recordDate,
                subscriptionStartDate,
                subscriptionEndDate,
                paymentDate,
                listingDate,
                currentSimulationDate
        );

        StockPrice price = stockPriceRepository.findById(instrument.getSymbol())
                .orElseThrow(() -> StockException.notFound("Price not found: " + instrument.getSymbol()));
        BigDecimal basePrice = price.getCurrentPrice();
        BigDecimal theoreticalExRightsPrice = offeringType == StockCapitalIncreaseOfferingType.SHAREHOLDER_ALLOCATION
                ? CorporateActionPolicy.calculateTheoreticalExRightsPrice(
                        instrument.getIssuedShares(),
                        basePrice,
                        shares,
                        issuePrice
                )
                : null;
        stockCorporateActionRepository.save(StockCorporateAction.paidInCapitalIncrease(
                instrument.getSymbol(),
                offeringType,
                shares,
                issuePrice,
                basePrice,
                theoreticalExRightsPrice,
                exRightsDate,
                recordDate,
                subscriptionStartDate,
                subscriptionEndDate,
                paymentDate,
                listingDate,
                CorporateActionPolicy.normalizeNullableDescription(request.description()),
                createdAt
        ));
        return OrderBookInstrumentResponseMapper.toResponse(instrument, price);
    }

    private OrderBookInstrumentResponse applyFreeShareDistribution(
            StockOrderBookInstrument instrument,
            CorporateActionRequest request,
            LocalDateTime createdAt,
            LocalDate currentSimulationDate
    ) {
        long shares = CorporateActionPolicy.requirePositiveShareQuantity(request.shareQuantity());
        LocalDate exRightsDate = request.exRightsDate();
        LocalDate listingDate = request.listingDate();
        CorporateActionPolicy.requireFreeShareDistributionDates(exRightsDate, listingDate, currentSimulationDate);

        StockPrice price = stockPriceRepository.findById(instrument.getSymbol())
                .orElseThrow(() -> StockException.notFound("Price not found: " + instrument.getSymbol()));
        BigDecimal basePrice = price.getCurrentPrice();
        BigDecimal theoreticalExRightsPrice = CorporateActionPolicy.calculateTheoreticalFreeSharePrice(
                instrument.getIssuedShares(),
                basePrice,
                shares
        );
        StockCorporateAction action = request.actionType() == StockCorporateActionType.BONUS_ISSUE
                ? StockCorporateAction.bonusIssue(
                        instrument.getSymbol(),
                        shares,
                        basePrice,
                        theoreticalExRightsPrice,
                        exRightsDate,
                        listingDate,
                        CorporateActionPolicy.normalizeNullableDescription(request.description()),
                        createdAt
                )
                : StockCorporateAction.stockDividend(
                        instrument.getSymbol(),
                        shares,
                        basePrice,
                        theoreticalExRightsPrice,
                        exRightsDate,
                        listingDate,
                        CorporateActionPolicy.normalizeNullableDescription(request.description()),
                        createdAt
                );
        stockCorporateActionRepository.save(action);
        return OrderBookInstrumentResponseMapper.toResponse(instrument, price);
    }

    private OrderBookInstrumentResponse applyStockSplit(
            StockOrderBookInstrument instrument,
            CorporateActionRequest request,
            LocalDateTime createdAt,
            LocalDate currentSimulationDate
    ) {
        Integer splitFrom = request.splitFrom();
        Integer splitTo = request.splitTo();
        CorporateActionPolicy.requireSupportedStockSplitRatio(splitFrom, splitTo);
        LocalDate listingDate = CorporateActionPolicy.requireStockSplitListingDate(request.listingDate(), currentSimulationDate);
        stockCorporateActionRepository.save(StockCorporateAction.stockSplit(
                instrument.getSymbol(),
                splitFrom,
                splitTo,
                listingDate,
                CorporateActionPolicy.normalizeNullableDescription(request.description()),
                createdAt
        ));
        return OrderBookInstrumentResponseMapper.toResponse(instrument, findPrice(instrument));
    }

    private OrderBookInstrumentResponse applyCashDividend(
            StockOrderBookInstrument instrument,
            CorporateActionRequest request,
            LocalDateTime createdAt,
            LocalDate currentSimulationDate
    ) {
        BigDecimal dividendAmount = CorporateActionPolicy.requirePositiveDividendAmount(request.dividendAmount());
        LocalDate exRightsDate = request.exRightsDate();
        LocalDate paymentDate = request.paymentDate();
        CorporateActionPolicy.requireCashDividendDates(exRightsDate, paymentDate, currentSimulationDate);

        StockPrice price = stockPriceRepository.findById(instrument.getSymbol())
                .orElseThrow(() -> StockException.notFound("Price not found: " + instrument.getSymbol()));
        BigDecimal basePrice = price.getCurrentPrice();

        stockCorporateActionRepository.save(StockCorporateAction.cashDividend(
                instrument.getSymbol(),
                dividendAmount,
                basePrice,
                basePrice,
                exRightsDate,
                paymentDate,
                CorporateActionPolicy.normalizeNullableDescription(request.description()),
                createdAt
        ));
        return OrderBookInstrumentResponseMapper.toResponse(instrument, price);
    }

    private OrderBookInstrumentResponse applyDelisting(
            StockOrderBookInstrument instrument,
            CorporateActionRequest request,
            LocalDateTime createdAt,
            LocalDate currentSimulationDate
    ) {
        LocalDate delistingDate = CorporateActionPolicy.requireDelistingDate(request.delistingDate(), currentSimulationDate);
        stockCorporateActionRepository.save(StockCorporateAction.delisting(
                instrument.getSymbol(),
                delistingDate,
                CorporateActionPolicy.normalizeNullableDescription(request.description()),
                createdAt
        ));
        return OrderBookInstrumentResponseMapper.toResponse(instrument, findPrice(instrument));
    }

    private void assertNoActiveExclusiveInstrumentAction(String symbol, StockCorporateActionType requestedActionType) {
        if (!EXCLUSIVE_INSTRUMENT_ACTION_TYPES.contains(requestedActionType)) {
            return;
        }
        if (stockCorporateActionRepository.existsBySymbolAndActionTypeInAndStatusIn(
                symbol,
                EXCLUSIVE_INSTRUMENT_ACTION_TYPES,
                ACTIVE_EXCLUSIVE_ACTION_STATUSES
        )) {
            throw StockException.conflict("Another instrument-changing corporate action is already in progress: " + symbol);
        }
    }

    private StockPrice findPrice(StockOrderBookInstrument instrument) {
        return stockPriceRepository.findById(instrument.getSymbol()).orElse(null);
    }
}
