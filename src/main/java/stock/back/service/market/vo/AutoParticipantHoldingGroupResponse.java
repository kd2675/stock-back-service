package stock.back.service.market.vo;

import java.util.List;

public record AutoParticipantHoldingGroupResponse(
        String userKey,
        Long accountId,
        List<AutoParticipantHoldingResponse> holdings
) {
}
