package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.AutoParticipantProfileType;
import stock.back.service.database.entity.StockAutoParticipantProfileConfig;
import stock.back.service.database.repository.StockAutoParticipantProfileConfigRepository;
import stock.back.service.market.vo.AutoParticipantProfileConfigRequest;
import stock.back.service.market.vo.AutoParticipantProfileConfigResponse;

@Service
@RequiredArgsConstructor
public class AutoParticipantProfileConfigService {

    private final StockAutoParticipantProfileConfigRepository stockAutoParticipantProfileConfigRepository;

    @Transactional
    public AutoParticipantProfileConfigResponse updateAutoParticipantProfileConfig(
            String profileTypeValue,
            AutoParticipantProfileConfigRequest request
    ) {
        AutoParticipantProfileType profileType = parseAutoParticipantProfileType(profileTypeValue);
        if (request == null) {
            throw StockException.badRequest("Auto participant profile config update is required");
        }
        AutoParticipantProfileConfigCommand command = AutoParticipantProfileConfigCommand.from(profileType, request);
        StockAutoParticipantProfileConfig config = stockAutoParticipantProfileConfigRepository.findById(profileType)
                .orElseGet(() -> command.create(profileType));
        command.applyTo(config);
        return AutoParticipantProfileConfigResponseMapper.toResponse(
                profileType,
                stockAutoParticipantProfileConfigRepository.save(config)
        );
    }

    private AutoParticipantProfileType parseAutoParticipantProfileType(String value) {
        try {
            return AutoParticipantProfileType.parseOrDefault(value);
        } catch (IllegalArgumentException exception) {
            throw StockException.badRequest("Unknown auto participant profile type");
        }
    }
}
