import React, { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { Mail, Lock } from '@/shared/icons';
import { GradientButton, ErrorBox, getErrorMessage } from '@/shared';
import { Input } from '@/app/components/ui/input';
import { Checkbox } from '@/app/components/ui/checkbox';
import { PluginSlot } from '@/app/components/PluginSlot';
import { Wordmark } from '@/shared/components/Wordmark';
import { useAuth } from '@/app/auth/useAuth';
import { useT } from '@/i18n';

export function Auth() {
  const navigate = useNavigate();
  const { login } = useAuth();
  const t = useT();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [rememberMe, setRememberMe] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const errorRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (error) errorRef.current?.focus();
  }, [error]);

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      await login(email, password, rememberMe);
      navigate('/flags');
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  return (
    <main className="min-h-screen bg-background flex flex-col items-center justify-center p-4 relative overflow-hidden transition-colors">
      <div className="absolute top-[-180px] right-[-100px] w-[450px] h-[450px] bg-gradient-to-br from-gradient-start/10 to-gradient-end/5 dark:from-gradient-start/8 dark:to-gradient-end/4 rounded-full blur-3xl"></div>

      <div className="w-full max-w-md relative z-10">
        <h1 className="sr-only">{t('auth.login')}</h1>
        <div className="bg-card backdrop-blur-2xl rounded-2xl shadow-xl border border-border transition-colors">
          <div className="p-6 sm:p-8">
            <div className="flex justify-center mb-4">
              <Wordmark text={t('common.appName')} size="xl" />
            </div>

            <div className="text-center mb-10">
              <p className="text-body font-medium text-muted-foreground">
                {t('auth.loginTagline')}
              </p>
            </div>

            {error && (
              <div ref={errorRef} tabIndex={-1} className="mb-4 outline-none" role="group">
                <ErrorBox>{error}</ErrorBox>
              </div>
            )}

            <form onSubmit={handleLogin} className="space-y-4">
              <div className="space-y-2">
                <label className="text-body-sm font-medium text-foreground/80" htmlFor="email">
                  {t('auth.email')}
                </label>
                <div className="relative">
                  <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none z-10">
                    <Mail size={18} className="text-muted-foreground/70" />
                  </div>
                  <Input
                    id="email"
                    type="email"
                    name="email"
                    autoComplete="email"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    maxLength={254}
                    className="pl-11"
                    placeholder={t('auth.emailPlaceholder')}
                    required
                  />
                </div>
              </div>

              <div className="space-y-2">
                <label className="text-body-sm font-medium text-foreground/80" htmlFor="password">
                  {t('auth.password')}
                </label>
                <div className="relative">
                  <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none z-10">
                    <Lock size={18} className="text-muted-foreground/70" />
                  </div>
                  <Input
                    id="password"
                    type="password"
                    name="password"
                    autoComplete="current-password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    maxLength={128}
                    className="pl-11"
                    placeholder={t('auth.passwordPlaceholder')}
                    required
                  />
                </div>
              </div>

              <div className="flex flex-wrap items-center justify-between gap-3 pt-2">
                <div className="flex items-center gap-2">
                  <Checkbox
                    id="remember"
                    checked={rememberMe}
                    onCheckedChange={(v) => setRememberMe(v === true)}
                  />
                  <label htmlFor="remember" className="block text-body-sm text-muted-foreground">
                    {t('auth.rememberMe')}
                  </label>
                </div>
                <div className="text-body-sm">
                  <Link
                    to="/forgot-password"
                    className="text-muted-foreground hover:text-foreground transition-colors"
                  >
                    {t('auth.forgotPassword')}
                  </Link>
                </div>
              </div>

              <GradientButton
                type="submit"
                loading={loading}
                size="lg"
                className="w-full mt-6 py-3 hover:shadow-xl"
              >
                {t('auth.login')}
              </GradientButton>
            </form>

            <div className="mt-6 space-y-4">
              <PluginSlot slotId="auth.social" />
              <PluginSlot slotId="auth.signup" />
            </div>
          </div>
        </div>
      </div>
    </main>
  );
}
