package stock.back.service.trading.biz;

import stock.back.service.common.exception.StockException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

final class AccountRecoveryCredentialGenerator {

    private static final Pattern ACCOUNT_CODE_PATTERN = Pattern.compile("^[A-Z0-9-]{6,32}$");
    private static final char[] RECOVERY_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final SecureRandom secureRandom = new SecureRandom();

    String normalizeAccountCode(String accountCode) {
        if (accountCode == null || accountCode.isBlank()) {
            throw StockException.badRequest("Account code is required");
        }
        String normalized = accountCode.trim().toUpperCase(Locale.ROOT);
        if (!ACCOUNT_CODE_PATTERN.matcher(normalized).matches()) {
            throw StockException.badRequest("Account code contains invalid characters");
        }
        return normalized;
    }

    String normalizeRecoveryCode(String recoveryCode) {
        if (recoveryCode == null || recoveryCode.isBlank()) {
            throw StockException.badRequest("Recovery code is required");
        }
        return recoveryCode.trim().toUpperCase(Locale.ROOT).replace(" ", "");
    }

    String generateAccountCode() {
        return "STK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }

    String generateRecoveryCode() {
        StringBuilder builder = new StringBuilder("RC-");
        for (int index = 0; index < 12; index++) {
            if (index > 0 && index % 4 == 0) {
                builder.append('-');
            }
            builder.append(RECOVERY_CODE_ALPHABET[secureRandom.nextInt(RECOVERY_CODE_ALPHABET.length)]);
        }
        return builder.toString();
    }

    String hashRecoveryCode(String recoveryCode) {
        return hashValue(normalizeRecoveryCode(recoveryCode));
    }

    String hashValue(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is not available", ex);
        }
    }

    boolean matchesRecoveryCode(String recoveryCode, String expectedHash) {
        if (expectedHash == null || expectedHash.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                hashRecoveryCode(recoveryCode).getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8)
        );
    }
}
