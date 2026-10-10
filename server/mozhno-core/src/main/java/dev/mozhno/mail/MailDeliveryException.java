package dev.mozhno.mail;

/**
 * Thrown when a notification cannot be delivered through its transport, for
 * example when the SMTP server is unreachable or rejects the message.
 * Unchecked so the requesting operation fails visibly and its transaction
 * rolls back instead of silently succeeding.
 */
public class MailDeliveryException extends RuntimeException {

    public MailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
