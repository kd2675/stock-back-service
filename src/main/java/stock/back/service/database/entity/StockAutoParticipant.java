package stock.back.service.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "stock_auto_participant")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockAutoParticipant {

    @Id
    @Column(name = "user_key", nullable = false, length = 64)
    private String userKey;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "profile_type", nullable = false, length = 40)
    private AutoParticipantProfileType profileType;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    public static StockAutoParticipant create(String userKey, String displayName, boolean enabled) {
        return create(userKey, displayName, enabled, AutoParticipantProfileType.defaultType());
    }

    public static StockAutoParticipant create(
            String userKey,
            String displayName,
            boolean enabled,
            AutoParticipantProfileType profileType
    ) {
        LocalDateTime now = LocalDateTime.now();
        StockAutoParticipant participant = new StockAutoParticipant();
        participant.userKey = userKey;
        participant.displayName = displayName;
        participant.enabled = enabled;
        participant.profileType = profileType == null ? AutoParticipantProfileType.defaultType() : profileType;
        participant.createdAt = now;
        participant.updatedAt = now;
        return participant;
    }

    public void update(String displayName, Boolean enabled, AutoParticipantProfileType profileType) {
        if (displayName != null && !displayName.isBlank()) {
            this.displayName = displayName;
        }
        if (enabled != null) {
            this.enabled = enabled;
            if (enabled) {
                this.withdrawnAt = null;
            }
        }
        if (profileType != null) {
            this.profileType = profileType;
        }
        this.updatedAt = LocalDateTime.now();
    }

    public void withdraw() {
        LocalDateTime now = LocalDateTime.now();
        this.enabled = false;
        this.withdrawnAt = now;
        this.updatedAt = now;
    }
}
