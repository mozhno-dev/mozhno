package dev.mozhno.auth;

import jakarta.validation.Valid;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import dev.mozhno.exception.InvalidCredentialsException;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST controller for authentication operations: login, token refresh, logout,
 * password reset, invite acceptance, and retrieving the currently authenticated user.
 *
 * @see AuthService
 * @see PasswordResetService
 * @see UserInviteService
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final UserInviteService userInviteService;

    @PostMapping("/login")
    @Timed(value = "auth.login", description = "Login request timing")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        boolean rememberMe = request.rememberMe() != null && request.rememberMe();
        return authService.login(request.email(), request.password(),
            request.provider(), request.params(), rememberMe);
    }

    @PostMapping("/refresh")
    public LoginResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshTokenRequest request) {
        String refreshToken = request != null ? request.refreshToken() : null;
        authService.logout(refreshToken);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public UserDto me(@AuthenticationPrincipal UserPrincipal user) {
        return authService.getCurrentUser(user.email());
    }

    private static final Map<String, String> FORGOT_PASSWORD_MESSAGES = Map.of(
        "ru", "Если email существует, ссылка для сброса отправлена",
        "en", "If the email exists, a reset link has been sent"
    );

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.sendResetEmail(request.email(), request.locale());
        String msg = FORGOT_PASSWORD_MESSAGES.getOrDefault(
            request.locale() != null ? request.locale() : "ru",
            FORGOT_PASSWORD_MESSAGES.get("en"));
        return ResponseEntity.ok(Map.of("message", msg));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.token(), request.password());
        return ResponseEntity.ok(Map.of("message", "Password has been reset successfully"));
    }

    @PostMapping("/accept-invite")
    public UserDto acceptInvite(@Valid @RequestBody AcceptInviteRequest request) {
        return userInviteService.acceptInvite(request.token(), request.name(), request.password());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidCredentials(InvalidCredentialsException ex) {
        return buildError(HttpStatus.UNAUTHORIZED, ex.getMessage(), "INVALID_CREDENTIALS");
    }

    @ExceptionHandler(RefreshTokenService.TokenReuseException.class)
    public ResponseEntity<Map<String, Object>> handleTokenReuse(RefreshTokenService.TokenReuseException ex) {
        return buildError(HttpStatus.UNAUTHORIZED, ex.getMessage(), "TOKEN_REUSE");
    }

    @ExceptionHandler(PasswordResetService.InvalidTokenException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidResetToken(PasswordResetService.InvalidTokenException ex) {
        return buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), "INVALID_RESET_TOKEN");
    }

    @ExceptionHandler(UserInviteService.InvalidInviteTokenException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidInviteToken(UserInviteService.InvalidInviteTokenException ex) {
        return buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), "INVALID_INVITE_TOKEN");
    }

    private ResponseEntity<Map<String, Object>> buildError(HttpStatus status, String message, String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("code", code);
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            body.put("traceId", traceId);
        }
        return ResponseEntity.status(status).body(body);
    }
}