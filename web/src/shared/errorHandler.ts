import { AppError, isAppError } from '@/shared/errors';
import { t, type MessageKey } from '@/i18n';

const ERROR_CODE_TO_KEY: Record<string, string> = {
  NETWORK: 'errors.network',
  UNAUTHORIZED: 'errors.unauthorized',
  FORBIDDEN: 'errors.forbidden',
  NOT_FOUND: 'errors.notFound',
  VALIDATION: 'errors.validation',
  RATE_LIMITED: 'errors.rateLimited',
  SERVER: 'errors.server',
  TIMEOUT: 'errors.timeout',
  UNKNOWN: 'errors.unexpected',
};

const SERVER_MESSAGE_TO_KEY: Record<string, MessageKey> = {
  'auth.error.invalid_credentials': 'errors.auth.invalidCredentials',
  'auth.error.email_password_required': 'errors.auth.emailPasswordRequired',
  'auth.error.account_suspended': 'errors.auth.accountSuspended',
  'auth.error.account_locked': 'errors.auth.accountLocked',
  'auth.error.no_auth_provider': 'errors.auth.noAuthProvider',
  'auth.error.email_not_verified': 'errors.auth.emailNotVerified',
  'Authentication required': 'errors.unauthorized',
  'Access denied': 'errors.forbidden',
};

const SERVER_CODE_TO_KEY: Record<string, MessageKey> = {
  UPLOAD_SIZE_EXCEEDED: 'errors.upload.fileTooLarge',
  UNSUPPORTED_IMAGE_FORMAT: 'errors.upload.unsupportedFormat',
  IMAGE_READ_ERROR: 'errors.upload.readError',
  IMAGE_TOO_LARGE: 'errors.upload.imageTooLarge',
  CANNOT_DELETE_SELF: 'users.errors.cannotDeleteSelf',
  MAIL_DELIVERY_FAILED: 'errors.mailDeliveryFailed',
};

function extractValidationDetails(error: AppError): string {
  const body = error.details as Record<string, unknown> | undefined;
  if (!body) return error.message;
  const details = body['details'] as { field: string; message: string }[] | undefined;
  if (!details || details.length === 0) return error.message;
  if (details.length === 1) {
    return details[0].message;
  }
  return details.map((d) => d.message).join('\n');
}

export function getErrorMessage(error: unknown): string {
  if (isAppError(error)) {
    const serverCode = (error.details as Record<string, unknown> | undefined)?.['code'] as string | undefined;
    if (serverCode && SERVER_CODE_TO_KEY[serverCode]) {
      const key = SERVER_CODE_TO_KEY[serverCode];
      if (serverCode === 'IMAGE_TOO_LARGE') {
        return t(key, { details: error.message });
      }
      if (serverCode === 'UPLOAD_SIZE_EXCEEDED') {
        const maxMatch = error.message.match(/(\d+)\s*MB/);
        return t(key, { max: maxMatch?.[1] ?? '2' });
      }
      return t(key);
    }
    if (error.code === 'VALIDATION') {
      const detailsText = extractValidationDetails(error);
      if (detailsText !== error.message) return detailsText;
    }
    if (error.message.startsWith('HTTP ')) {
      return error.message;
    }
    if (error.code === 'UNAUTHORIZED') {
      const i18nKey = SERVER_MESSAGE_TO_KEY[error.message];
      if (i18nKey) return t(i18nKey);
    }
    const key = ERROR_CODE_TO_KEY[error.code];
    if (key) {
      if (error.code === 'UNKNOWN' && error.message) return error.message;
      return t(key as MessageKey);
    }
    return error.message;
  }

  if (error instanceof Error) {
    return error.message;
  }

  return String(error);
}

export function getErrorCode(error: unknown): string {
  if (isAppError(error)) {
    return error.code;
  }
  return 'UNKNOWN';
}

export function shouldRetry(error: unknown): boolean {
  if (isAppError(error)) {
    return error.code === 'NETWORK' || error.code === 'TIMEOUT' || error.code === 'SERVER';
  }
  return false;
}

export function shouldRedirect(error: unknown): boolean {
  if (isAppError(error)) {
    return error.code === 'UNAUTHORIZED';
  }
  return false;
}

export { isAppError, AppError };
export type { ErrorCode } from '@/shared/errors';
