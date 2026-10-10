import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { AppError, isAppError, createAppError } from '@/shared/errors';
import { getErrorMessage, getErrorCode, shouldRetry, shouldRedirect } from '@/shared/errorHandler';

describe('AppError', () => {
  it('creates an error with code and status', () => {
    const err = new AppError('Not found', 'NOT_FOUND', 404);
    expect(err.name).toBe('AppError');
    expect(err.message).toBe('Not found');
    expect(err.code).toBe('NOT_FOUND');
    expect(err.statusCode).toBe(404);
    expect(err instanceof Error).toBe(true);
  });

  it('creates an error with details', () => {
    const details = { field: 'name', issue: 'required' };
    const err = new AppError('Validation failed', 'VALIDATION', 422, details);
    expect(err.details).toEqual(details);
  });
});

describe('isAppError', () => {
  it('returns true for AppError instances', () => {
    expect(isAppError(new AppError('x', 'UNKNOWN'))).toBe(true);
  });

  it('returns false for plain Error', () => {
    expect(isAppError(new Error('x'))).toBe(false);
  });

  it('returns false for string', () => {
    expect(isAppError('error')).toBe(false);
  });

  it('returns false for null', () => {
    expect(isAppError(null)).toBe(false);
  });
});

describe('createAppError', () => {
  it('maps 401 to UNAUTHORIZED', () => {
    const err = createAppError('No access', 401);
    expect(err.code).toBe('UNAUTHORIZED');
  });

  it('maps 403 to FORBIDDEN', () => {
    const err = createAppError('No rights', 403);
    expect(err.code).toBe('FORBIDDEN');
  });

  it('maps 404 to NOT_FOUND', () => {
    const err = createAppError('Missing', 404);
    expect(err.code).toBe('NOT_FOUND');
  });

  it('maps 422 to VALIDATION', () => {
    const err = createAppError('Bad data', 422);
    expect(err.code).toBe('VALIDATION');
  });

  it('maps 500+ to SERVER', () => {
    const err = createAppError('Down', 500);
    expect(err.code).toBe('SERVER');
  });

  it('maps other statuses to UNKNOWN', () => {
    const err = createAppError('Redirect?', 302);
    expect(err.code).toBe('UNKNOWN');
  });
});

describe('getErrorMessage', () => {
  beforeAll(() => {
    localStorage.setItem('mozhno-locale', 'ru');
  });

  afterAll(() => {
    localStorage.removeItem('mozhno-locale');
  });

  it('returns AppError user message', () => {
    const msg = getErrorMessage(new AppError('raw', 'NOT_FOUND'));
    expect(msg).toBe('Запрашиваемый ресурс не найден.');
  });

  it('falls back to raw message for unknown code', () => {
    const msg = getErrorMessage(new AppError('raw msg', 'UNKNOWN'));
    expect(msg).toBe('raw msg');
  });

  it('returns Error.message for plain Error', () => {
    const msg = getErrorMessage(new Error('plain error'));
    expect(msg).toBe('plain error');
  });

  it('returns localized message for VALIDATION error', () => {
    const msg = getErrorMessage(new AppError('Bad data', 'VALIDATION'));
    expect(msg).toBe('Данные заполнены некорректно. Проверьте поля формы.');
  });

  it('returns details message for VALIDATION with single field error', () => {
    const err = new AppError('Validation failed: email is required (email)', 'VALIDATION', 400, {
      error: 'Validation failed: email is required (email)',
      code: 'VALIDATION_ERROR',
      details: [{ field: 'email', message: 'email is required' }],
    });
    const msg = getErrorMessage(err);
    expect(msg).toBe('email is required');
  });

  it('returns joined details for VALIDATION with multiple field errors', () => {
    const err = new AppError(
      'Validation failed: must not be blank (context[0].operator); must not be blank (context[0].contextValues)',
      'VALIDATION', 400, {
      error: 'Validation failed',
      code: 'VALIDATION_ERROR',
      details: [
        { field: 'context[0].operator', message: 'must not be blank' },
        { field: 'context[0].contextValues', message: 'must not be blank' },
      ],
    });
    const msg = getErrorMessage(err);
    expect(msg).toContain('must not be blank');
    expect(msg).toContain('\n');
  });

  it('returns raw message for HTTP-prefixed errors', () => {
    const msg = getErrorMessage(new AppError('HTTP 500 Internal Error', 'SERVER'));
    expect(msg).toBe('HTTP 500 Internal Error');
  });

  it('maps MAIL_DELIVERY_FAILED server code to localized message', () => {
    const err = new AppError('Could not send the email', 'SERVER', 500, {
      error: 'Could not send the email',
      code: 'MAIL_DELIVERY_FAILED',
    });
    const msg = getErrorMessage(err);
    expect(msg).toBe('Не удалось отправить письмо. Проверьте настройки почты и попробуйте снова.');
  });

  it('returns String for non-error values', () => {
    expect(getErrorMessage('str')).toBe('str');
    expect(getErrorMessage(42)).toBe('42');
  });
});

describe('getErrorCode', () => {
  it('returns AppError code', () => {
    expect(getErrorCode(new AppError('x', 'NETWORK'))).toBe('NETWORK');
  });

  it('returns UNKNOWN for non-AppError', () => {
    expect(getErrorCode(new Error('x'))).toBe('UNKNOWN');
    expect(getErrorCode('str')).toBe('UNKNOWN');
  });
});

describe('shouldRetry', () => {
  it('returns true for NETWORK', () => {
    expect(shouldRetry(new AppError('x', 'NETWORK'))).toBe(true);
  });

  it('returns true for TIMEOUT', () => {
    expect(shouldRetry(new AppError('x', 'TIMEOUT'))).toBe(true);
  });

  it('returns true for SERVER', () => {
    expect(shouldRetry(new AppError('x', 'SERVER'))).toBe(true);
  });

  it('returns false for VALIDATION', () => {
    expect(shouldRetry(new AppError('x', 'VALIDATION'))).toBe(false);
  });

  it('returns false for UNAUTHORIZED', () => {
    expect(shouldRetry(new AppError('x', 'UNAUTHORIZED'))).toBe(false);
  });

  it('returns false for non-AppError', () => {
    expect(shouldRetry(new Error('x'))).toBe(false);
  });
});

describe('shouldRedirect', () => {
  it('returns true for UNAUTHORIZED', () => {
    expect(shouldRedirect(new AppError('x', 'UNAUTHORIZED'))).toBe(true);
  });

  it('returns false for other codes', () => {
    expect(shouldRedirect(new AppError('x', 'FORBIDDEN'))).toBe(false);
  });

  it('returns false for non-AppError', () => {
    expect(shouldRedirect(new Error('x'))).toBe(false);
  });
});
