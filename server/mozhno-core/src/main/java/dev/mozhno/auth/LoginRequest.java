package dev.mozhno.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

@Schema(description = "Login request payload containing credentials and optional provider details")
public record LoginRequest(
    @NotBlank @Email
    @Schema(description = "User email address", example = "user@example.com")
    String email,

    @Schema(description = "Plaintext password (may be null for passwordless flows)", nullable = true)
    String password,

    @Schema(description = "Whether to persist the session longer")
    Boolean rememberMe,

    @Schema(description = "Optional provider identifier for pluggable auth", nullable = true)
    String provider,

    @Schema(description = "Additional provider-specific parameters", nullable = true)
    Map<String, String> params
) {}