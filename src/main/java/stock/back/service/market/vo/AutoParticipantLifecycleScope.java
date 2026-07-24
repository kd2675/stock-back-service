package stock.back.service.market.vo;

public enum AutoParticipantLifecycleScope {
    CURRENT,
    WITHDRAWN;

    public static AutoParticipantLifecycleScope effective(AutoParticipantLifecycleScope scope) {
        return scope == null ? CURRENT : scope;
    }
}
