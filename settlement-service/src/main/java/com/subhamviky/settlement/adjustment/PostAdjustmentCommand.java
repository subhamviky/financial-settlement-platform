package com.subhamviky.settlement.adjustment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** Body of POST /api/v1/settlements/{id}/adjustments; the settlement id comes from the path. */
public record PostAdjustmentCommand(
        UUID settlementId,
        @NotNull AdjustmentType type,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotBlank @Size(max = 500) String reason,
        @NotBlank @Size(max = 100) String reference) {

    public PostAdjustmentCommand withSettlementId(UUID id) {
        return new PostAdjustmentCommand(id, type, amount, reason, reference);
    }
}
