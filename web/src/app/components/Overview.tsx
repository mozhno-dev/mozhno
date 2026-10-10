import { useMemo } from 'react';
import { useNavigate } from 'react-router';
import { motion, useReducedMotion } from 'motion/react';
import { useT, useLocale, type MessageKey } from '@/i18n';
import { useAuth } from '@/app/auth/useAuth';
import {
  useProjectQuery,
  useOverviewQuery,
  useEnrichedFlagsQuery,
  useEnvironmentsQuery,
} from '@/app/hooks/queries';
import { usePermissions } from '@/app/hooks/usePermissions';
import {
  Card,
  GradientButton,
  ErrorBox,
  InfoTip,
  getEnvColor,
  timeAgo,
  formatCompactCount,
  Fab,
  getCase,
  getGender,
  getActionParticiple,
  russianPlural,
} from '@/shared';
import { Skeleton } from '@/app/components/ui/skeleton';
import { Avatar, AvatarFallback } from '@/app/components/ui/avatar';
import { AuthedAvatarImage } from '@/app/components/AuthedAvatarImage';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/app/components/ui/dropdown-menu';
import { computeDrift } from '@/shared/overviewAggregation';
import type { DriftRow } from '@/shared/overviewAggregation';
import { Plus, UserPlus, Key, Check, MoreHorizontal } from '@/shared/icons';
import { api } from '@/api';
import type { OverviewEnvironmentStat, OverviewResponse, AuditEvent } from '@/api';

const DRIFT_LIMIT = 8;
const EASE = [0.16, 1, 0.3, 1] as const;

type TFn = (key: MessageKey, params?: Record<string, string>) => string;

function Section({
  index = 0,
  className,
  children,
}: {
  index?: number;
  className?: string;
  children: React.ReactNode;
}) {
  const reduce = useReducedMotion();
  return (
    <motion.section
      initial={reduce ? false : { opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.28, delay: reduce ? 0 : index * 0.05, ease: EASE }}
      className={className}
    >
      {children}
    </motion.section>
  );
}

export function Overview() {
  const t = useT();
  const navigate = useNavigate();
  const { user } = useAuth();
  const { canWrite, canManage: isAdmin } = usePermissions();

  const { data: project } = useProjectQuery();
  const projectId = project?.id ?? null;

  const { data: overview, isLoading, isError } = useOverviewQuery(projectId);
  const { data: enriched } = useEnrichedFlagsQuery(projectId);
  const { data: environments = [] } = useEnvironmentsQuery();

  const flags = useMemo(() => enriched?.flags ?? [], [enriched?.flags]);
  const envRefs = useMemo(
    () => environments.map((e) => ({ id: e.id, name: e.name, color: getEnvColor(e) })),
    [environments],
  );
  const envColorFor = useMemo(() => {
    const map = new Map(environments.map((e) => [e.id, getEnvColor(e)]));
    return (id: number) => map.get(id) ?? getEnvColor(id);
  }, [environments]);
  const driftRows = useMemo(() => computeDrift(flags, envRefs), [flags, envRefs]);
  const totalActive = useMemo(() => flags.filter((f) => !f.archived).length, [flags]);

  if (isLoading || !overview) {
    return <OverviewSkeleton />;
  }

  if (isError) {
    return <ErrorBox>{t('overview.error')}</ErrorBox>;
  }

  const greetingName = user?.name || user?.email || '';

  const onboardingValues = Object.values(overview.onboarding);
  const onboardingDone = onboardingValues.filter(Boolean).length;
  const showOnboarding = onboardingDone < onboardingValues.length;

  return (
    <div className="space-y-8">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <h1 className="text-h2 sm:text-h1 font-heading font-bold tracking-tight">
          {t('overview.greeting', { name: greetingName })}
        </h1>
        {(canWrite || isAdmin) && <HeaderActions t={t} canWrite={canWrite} isAdmin={isAdmin} />}
      </div>

      <Section index={0}>
        <OverviewKpis t={t} totals={overview.totals} />
      </Section>

      <Section index={1} className="space-y-4">
        <h2 className="text-h3 font-heading font-semibold tracking-tight">{t('overview.environments.title')}</h2>
        <p className="text-caption text-muted-foreground/80 -mt-2">{t('overview.environments.subtitle')}</p>
        <EnvironmentsGrid t={t} environments={overview.environments} colorFor={envColorFor} />
      </Section>

      <Section index={2} className="space-y-4">
        <h2 className="text-h3 font-heading font-semibold tracking-tight">{t('overview.drift.title')}</h2>
        <p className="text-caption text-muted-foreground/80 -mt-2">{t('overview.drift.subtitle')}</p>
        <DriftDashboard t={t} rows={driftRows} envRefs={envRefs} totalActive={totalActive} />
      </Section>

      <Section index={3}>
        {showOnboarding ? (
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
            <div className="lg:col-span-2">
              <ActivityFeed t={t} events={overview.recentActivity} />
            </div>
            <OnboardingChecklist t={t} onboarding={overview.onboarding} canWrite={canWrite} />
          </div>
        ) : (
          <div className="lg:max-w-3xl">
            <ActivityFeed t={t} events={overview.recentActivity} />
          </div>
        )}
      </Section>
      {canWrite && <Fab onClick={() => navigate('/flags?new=1')} label={t('overview.quickActions.createFlag')} />}
    </div>
  );
}

function HeaderActions({ t, canWrite, isAdmin }: { t: TFn; canWrite: boolean; isAdmin: boolean }) {
  const navigate = useNavigate();
  return (
    <div className="flex items-center gap-2 shrink-0 flex-wrap">
      {canWrite && (
        <div className="hidden sm:block">
          <GradientButton icon={<Plus size={16} />} onClick={() => navigate('/flags?new=1')}>
            {t('overview.quickActions.createFlag')}
          </GradientButton>
        </div>
      )}
      {isAdmin && (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              type="button"
              aria-label={t('overview.quickActions.more')}
              className="inline-flex items-center justify-center size-9 rounded-lg border border-border bg-card text-muted-foreground transition-colors hover:bg-accent hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/40"
            >
              <MoreHorizontal size={18} />
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-56">
            <DropdownMenuItem className="cursor-pointer" onClick={() => navigate('/users')}>
              <UserPlus size={16} />
              {t('overview.quickActions.inviteUser')}
            </DropdownMenuItem>
            <DropdownMenuItem className="cursor-pointer" onClick={() => navigate('/apikeys')}>
              <Key size={16} />
              {t('overview.quickActions.issueApiKey')}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      )}
    </div>
  );
}

type KpiTone = 'neutral' | 'warning' | 'destructive' | 'brand';

function toneClass(tone: KpiTone, value: number): string {
  if (value <= 0 || tone === 'neutral') return '';
  if (tone === 'warning') return 'text-palette-warning-600 dark:text-palette-warning-700';
  if (tone === 'destructive') return 'text-destructive';
  return 'text-brand';
}

function OverviewKpis({ t, totals }: { t: TFn; totals: OverviewResponse['totals'] }) {
  const navigate = useNavigate();

  const dotFor = (tone: KpiTone): string => {
    if (tone === 'warning') return 'bg-warning';
    if (tone === 'destructive') return 'bg-destructive';
    if (tone === 'brand') return 'bg-brand';
    return 'bg-muted-foreground/30';
  };

  const items: {
    label: string;
    value: number;
    tone: KpiTone;
    tip: string;
    sub?: string;
    nav?: string;
  }[] = [
    {
      label: t('overview.kpi.stale'),
      value: totals.staleFlags,
      tone: 'warning',
      tip: t('overview.hints.stale'),
      nav: '/flags?stale=1',
    },
    {
      label: t('overview.kpi.killswitches'),
      value: totals.activeKillswitches,
      tone: 'destructive',
      tip: t('overview.hints.killswitches'),
      nav: '/flags?flagType=KILLSWITCH',
    },
    {
      label: t('overview.kpi.rollouts'),
      value: totals.rolloutsInProgress,
      tone: 'brand',
      tip: t('overview.hints.rollouts'),
    },
    {
      label: t('overview.kpi.totalFlags'),
      value: totals.totalFlags,
      tone: 'neutral',
      tip: t('overview.hints.totalFlags'),
      sub:
        totals.archivedFlags > 0
          ? t('overview.kpi.archived', { count: String(totals.archivedFlags) })
          : undefined,
    },
  ];

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
      {items.map((it) => {
        const isClickable = !!it.nav && it.value > 0;
        return (
          <Card
            key={it.label}
            padded
            onClick={isClickable ? () => navigate(it.nav!) : undefined}
            className={isClickable ? 'group' : ''}
          >
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2 min-w-0">
                <span className={`size-2.5 rounded-full shrink-0 ${dotFor(it.tone)}`} aria-hidden="true" />
                <span
                  className={`text-h2 font-heading font-bold tabular-nums ${toneClass(it.tone, it.value)} ${isClickable ? 'group-hover:underline underline-offset-4' : ''}`}
                >
                  {it.value}
                </span>
              </div>
              <div className="flex items-center gap-1.5 shrink-0">
                <span className="text-caption text-muted-foreground">{it.label}</span>
                <InfoTip text={it.tip} size={11} />
                {it.sub && (
                  <span className="text-caption text-muted-foreground/60">· {it.sub}</span>
                )}
              </div>
            </div>
          </Card>
        );
      })}
    </div>
  );
}

function EnvironmentsGrid({
  t,
  environments,
  colorFor,
}: {
  t: TFn;
  environments: OverviewEnvironmentStat[];
  colorFor: (id: number) => string;
}) {
  if (environments.length === 0) {
    return (
      <Card padded className="py-10 text-center text-body-sm text-muted-foreground">
        {t('overview.environments.empty')}
      </Card>
    );
  }
  return (
    <div className="grid gap-3 [grid-template-columns:repeat(auto-fit,minmax(min(280px,100%),1fr))]">
      {environments.map((env, i) => (
        <EnvironmentStatCard key={env.environmentId} t={t} env={env} index={i} color={colorFor(env.environmentId)} />
      ))}
    </div>
  );
}

const RING_R = 36;
const RING_C = 2 * Math.PI * RING_R;

function EnvironmentStatCard({
  t,
  env,
  index,
  color,
}: {
  t: TFn;
  env: OverviewEnvironmentStat;
  index: number;
  color: string;
}) {
  const reduce = useReducedMotion();
  const enabledPct = env.totalFlags > 0 ? Math.round((env.enabledCount / env.totalFlags) * 100) : 0;
  const totalEval = env.evalTrue48h + env.evalFalse48h;
  const ringOffset = RING_C * (1 - enabledPct / 100);

  return (
    <Card className="h-full">
      <div className="p-4">
        <div className="flex items-center gap-2 mb-4">
          <span
            className="w-6 h-6 rounded-lg grid place-items-center text-caption font-bold border"
            style={{
              backgroundColor: `${color}1f`,
              borderColor: `${color}33`,
              color: color,
            }}
          >
            {env.environmentName.charAt(0).toUpperCase()}
          </span>
          <span className="font-semibold text-body truncate">{env.environmentName}</span>
          <span className="ml-auto shrink-0 text-caption font-medium text-muted-foreground">
            {env.connectedApps > 0
              ? t('overview.environments.apps', { count: String(env.connectedApps) })
              : t('overview.environments.noApps')}
          </span>
        </div>

        <div className="flex items-center gap-4">
          <div className="relative w-24 h-24 shrink-0">
            <svg viewBox="0 0 96 96" className="w-24 h-24 -rotate-90">
              <circle cx="48" cy="48" r={RING_R} fill="none" stroke="var(--color-border)" strokeWidth="9" />
              <motion.circle
                cx="48"
                cy="48"
                r={RING_R}
                fill="none"
                stroke={color}
                strokeWidth="9"
                strokeLinecap="round"
                strokeDasharray={RING_C}
                initial={{ strokeDashoffset: reduce ? ringOffset : RING_C }}
                animate={{ strokeDashoffset: ringOffset }}
                transition={{ duration: reduce ? 0 : 0.6, delay: reduce ? 0 : 0.15 + index * 0.05, ease: EASE }}
              />
            </svg>
            <div className="absolute inset-0 flex flex-col items-center justify-center">
              <span className="text-h3 font-heading font-bold leading-none tracking-tight tabular-nums">
                {enabledPct}%
              </span>
              <span className="text-[10px] leading-none text-muted-foreground mt-1 tabular-nums">
                {env.enabledCount}/{env.totalFlags}
              </span>
            </div>
          </div>

          <div className="flex-1 min-w-0 space-y-2">
            <div className="flex items-center justify-between text-caption">
              <span className="inline-flex items-center gap-1.5 text-muted-foreground">
                {t('overview.environments.evaluations')}
                <InfoTip text={t('overview.hints.evaluations')} />
              </span>
              <span className="font-semibold tabular-nums">{formatCompactCount(totalEval)}</span>
            </div>
            <div className="flex items-center justify-between text-caption">
              <span className="inline-flex items-center gap-1.5 text-muted-foreground">
                {t('overview.environments.rollouts')}
                <InfoTip text={t('overview.hints.rollouts')} />
              </span>
              <span
                className={`font-semibold tabular-nums ${env.rolloutCount > 0 ? 'text-brand' : ''}`}
              >
                {env.rolloutCount}
              </span>
            </div>
            <div className="flex items-center justify-between text-caption">
              <span className="inline-flex items-center gap-1.5 text-muted-foreground">
                {t('overview.environments.stale')}
                <InfoTip text={t('overview.hints.stale')} />
              </span>
              <span
                className={`font-semibold tabular-nums ${env.staleCount > 0 ? 'text-palette-warning-600 dark:text-palette-warning-700' : ''}`}
              >
                {env.staleCount}
              </span>
            </div>
          </div>
        </div>
      </div>
    </Card>
  );
}

function DriftDashboard({
  t,
  rows,
  envRefs,
  totalActive,
}: {
  t: TFn;
  rows: DriftRow[];
  envRefs: { id: number; name: string; color: string }[];
  totalActive: number;
}) {
  const navigate = useNavigate();
  const shown = rows.slice(0, DRIFT_LIMIT);

  const driftCount = rows.filter((r) => r.status === 'drift').length;
  const rolloutCount = rows.filter((r) => r.status === 'rollout').length;
  const syncedCount = totalActive - rows.length;

  if (rows.length === 0) {
    return (
      <Card padded className="py-12 text-center">
        <div className="mx-auto mb-3 flex size-12 items-center justify-center rounded-full bg-success/10">
          <Check size={22} className="text-success" />
        </div>
        <p className="text-body font-semibold text-foreground">{t('overview.drift.empty')}</p>
      </Card>
    );
  }

  const arcLen = 87.96;
  const arc = (fraction: number) => Math.round(arcLen * (1 - fraction));

  return (
    <div>
      {/* Gauge instruments */}
      <div className="grid grid-cols-3 gap-2.5 mb-6">
        <GaugeCard
          value={driftCount}
          label={t('overview.drift.gaugeDrift')}
          hint={t('overview.hints.driftDrift')}
          color="var(--warning)"
          arcDash={arcLen}
          arcOffset={arc(driftCount / totalActive)}
        />
        <GaugeCard
          value={rolloutCount}
          label={t('overview.drift.gaugeRollout')}
          hint={t('overview.hints.driftRollout')}
          color="var(--brand)"
          arcDash={arcLen}
          arcOffset={arc(rolloutCount / totalActive)}
        />
        <GaugeCard
          value={syncedCount}
          label={t('overview.drift.gaugeSynced')}
          hint={t('overview.hints.driftSynced')}
          color="var(--success)"
          arcDash={arcLen}
          arcOffset={arc(syncedCount / totalActive)}
        />
      </div>

      {/* List header */}
      <div className="flex items-baseline justify-between mb-2.5">
        <span className="text-body font-semibold text-muted-foreground">
          {t('overview.drift.attention')}
        </span>
        {rows.length > DRIFT_LIMIT && (
          <button
            type="button"
            onClick={() => navigate('/flags')}
            className="text-body-sm font-medium text-brand hover:underline"
          >
            {t('overview.drift.showAll', { count: String(rows.length) })}
          </button>
        )}
      </div>

      {/* Flag rows */}
      <div className="flex flex-col gap-1.5">
        {shown.map((row) => (
          <DriftFlagRow
            key={row.flagId}
            t={t}
            row={row}
            envRefs={envRefs}
          />
        ))}
      </div>
    </div>
  );
}

function GaugeCard({
  value,
  label,
  hint,
  color,
  arcDash,
  arcOffset,
}: {
  value: number;
  label: string;
  hint: string;
  color: string;
  arcDash: number;
  arcOffset: number;
}) {
  return (
    <div className="flex flex-col items-center rounded-xl bg-card px-3 pt-3 pb-2.5 text-center shadow-sm">
      <div className="relative grid place-items-center">
        <div
          className="text-[18px] font-bold tracking-[-0.02em] leading-none tabular-nums"
          style={{ gridArea: '1/1', zIndex: 1,   translate: '0 14px', color }}
        >
          {value}
        </div>
        <svg
          width="72"
          height="44"
          viewBox="0 0 72 40"
          style={{ gridArea: '1/1' }}
        >
          <path
            d="M 8 34 A 28 28 0 0 1 64 34"
            fill="none"
            stroke="var(--border)"
            strokeWidth="6"
            strokeLinecap="round"
          />
          <path
            d="M 8 34 A 28 28 0 0 1 64 34"
            fill="none"
            stroke={color}
            strokeWidth="6"
            strokeLinecap="round"
            strokeDasharray={`${arcDash} ${arcDash}`}
            strokeDashoffset={arcOffset}
          />
        </svg>
      </div>
      <div className="flex items-center gap-1 mt-0">
        <span className="text-caption text-muted-foreground">{label}</span>
        <InfoTip text={hint} size={10} />
      </div>
    </div>
  );
}

function DriftFlagRow({
  t,
  row,
  envRefs,
}: {
  t: TFn;
  row: DriftRow;
  envRefs: { id: number; name: string; color: string }[];
}) {
  const navigate = useNavigate();
  const isDrift = row.status === 'drift';

  const dotColor = (state: string) => {
    if (state === 'on') return 'var(--success)';
    if (state === 'rollout') return 'var(--brand)';
    return 'var(--muted-foreground)';
  };

  const handleNavigate = () => {
    navigate(`/flags?open=${row.flagKey}`);
  };

  return (
    <div
      role="button"
      tabIndex={0}
      onClick={handleNavigate}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          handleNavigate();
        }
      }}
      className="rounded-xl bg-card shadow-md overflow-hidden cursor-pointer hover:shadow-md hover:-translate-y-px transition-all duration-[--duration-fast] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
      style={{ borderLeft: `3px solid ${isDrift ? 'var(--warning)' : 'var(--brand)'}` }}
    >
      {/* Desktop: table layout */}
      <div className="hidden sm:block">
        <div className="table table-fixed w-full">
          <div className="table-row">
            <div className="table-cell align-middle py-2 pl-4" style={{ width: 155 }}>
              <div className="text-body-sm font-semibold leading-tight truncate">{row.name}</div>
              <div className="text-caption text-muted-foreground/70 dark:text-muted-foreground font-mono mt-px truncate">{row.flagKey}</div>
            </div>
            {row.cells.map((cell) => {
              const env = envRefs.find((e) => e.id === cell.environmentId);
              const stateText =
                cell.state === 'rollout' ? `${cell.percentage}%` : cell.state === 'on' ? t('overview.drift.on') : t('overview.drift.off');
              const stateColor =
                cell.state === 'on' ? 'text-success' : cell.state === 'off' ? 'text-muted-foreground/70 dark:text-muted-foreground' : 'text-brand';
              return (
                <div key={cell.environmentId} className="table-cell align-middle text-center py-2">
                  <div className="inline-flex items-center gap-2 min-w-0">
                    <span
                      className="size-2 rounded-full shrink-0"
                      style={{ backgroundColor: dotColor(cell.state), opacity: cell.state === 'off' ? 0.35 : 1 }}
                    />
                    <span className="text-caption font-medium text-muted-foreground truncate">{env?.name ?? '?'}</span>
                    <span className={`text-caption font-semibold shrink-0 ${stateColor}`}>{stateText}</span>
                  </div>
                </div>
              );
            })}
            <div className="table-cell align-middle py-2 pr-4 text-right">
              <span
                className={`inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-caption font-bold ${
                  isDrift
                    ? 'text-palette-warning-600 dark:text-palette-warning-700 bg-warning/10 border border-warning/20'
                    : 'text-brand bg-brand/10 border border-brand/20'
                }`}
              >
                {isDrift ? t('overview.drift.chipDrift') : t('overview.drift.chipRollout')}
              </span>
            </div>
          </div>
        </div>
      </div>

      {/* Mobile: compact */}
      <div className="flex items-center justify-between gap-3 py-2.5 pl-4 pr-3 sm:hidden">
        <div className="min-w-0 flex-1">
          <div className="text-body-sm font-semibold leading-tight truncate">{row.name}</div>
          <div className="text-caption text-muted-foreground/70 dark:text-muted-foreground font-mono mt-px truncate">{row.flagKey}</div>
        </div>
        <div className="shrink-0">
          <span
            className={`inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-caption font-bold ${
              isDrift
                ? 'text-palette-warning-600 dark:text-palette-warning-700 bg-warning/10 border border-warning/20'
                : 'text-brand bg-brand/10 border border-brand/20'
            }`}
          >
            {isDrift ? t('overview.drift.chipDrift') : t('overview.drift.chipRollout')}
          </span>
        </div>
      </div>
    </div>
  );
}

function ActivityFeed({ t, events }: { t: TFn; events: AuditEvent[] }) {
  const navigate = useNavigate();
  const { locale } = useLocale();
  const isRu = locale === 'ru';

  const actionTone = (action: string): string => {
    const suffix = action.split('.').pop() ?? action;
    if (suffix === 'created' || suffix === 'unarchived') return 'bg-success';
    if (suffix === 'deleted' || suffix === 'purged') return 'bg-destructive';
    if (suffix === 'archived') return 'bg-warning';
    return 'bg-brand';
  };

  const sentenceText = (e: AuditEvent) => {
    if (isRu) {
      const gender = getGender(e.resourceType);
      const action = getActionParticiple(e.action, gender);
      const resource = getCase(e.resourceType, 'nom');
      return `${action.charAt(0).toUpperCase() + action.slice(1)} ${resource.toLowerCase()}`;
    }
    return `${getActionLabel(t, e.action)} ${getResourceLabel(t, e.resourceType)}`;
  };

  return (
    <div className="space-y-3">
      <div className="flex items-baseline justify-between">
        <h2 className="text-h3 font-heading font-semibold tracking-tight">{t('overview.activity.title')}</h2>
        <button
          type="button"
          onClick={() => navigate('/audit')}
          className="text-caption font-medium text-brand hover:underline focus-visible:outline-none focus-visible:underline"
        >
          {t('overview.activity.seeAll')}
        </button>
      </div>

      {events.length === 0 ? (
        <div className="py-12 text-center text-body-sm text-muted-foreground bg-secondary rounded-xl">
          {t('overview.activity.empty')}
        </div>
      ) : (
        <div className="space-y-1.5">
          {events.map((e) => (
            <button
              key={e.id}
              type="button"
              onClick={() => navigate(`/audit?open=${e.id}`)}
              className="group w-full flex items-center gap-3 px-3 py-2.5 rounded-xl bg-card shadow-sm hover:shadow-md hover:-translate-y-px transition-all duration-[--duration-fast] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 text-left"
            >
              <span className={`size-1.5 rounded-full shrink-0 ${actionTone(e.action)}`} aria-hidden="true" />
              <Avatar className="w-7 h-7 shrink-0">
                {e.userId ? (
                  <AuthedAvatarImage src={api.users.getAvatarUrl(e.userId)} alt={e.userName ?? ''} />
                ) : null}
                <AvatarFallback
                  className="text-caption font-semibold text-primary-foreground"
                  style={avatarStyle(e.userName || 'system')}
                >
                  {initials(e.userName)}
                </AvatarFallback>
              </Avatar>
              <div className="min-w-0 flex-1">
                <div className="text-body-sm truncate">
                  <span className="text-muted-foreground">
                    {sentenceText(e)}
                    {e.resourceName && (
                      <span className="font-medium text-foreground"> «{e.resourceName}»</span>
                    )}
                  </span>
                </div>
              </div>
              <span className="shrink-0 text-caption text-muted-foreground/60 tabular-nums">
                {timeAgo(e.createdAt)}
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

const AUDIT_ACTIONS = ['created', 'updated', 'deleted', 'archived', 'unarchived', 'purged'];
const AUDIT_RESOURCES = [
  'flag',
  'user',
  'tag',
  'apikey',
  'segment',
  'project',
  'environment',
  'context',
  'strategy',
  'integration',
];

function getActionLabel(t: TFn, action: string): string {
  const suffix = action.split('.').pop() ?? action;
  if (AUDIT_ACTIONS.includes(suffix)) return t(`audit.action.${suffix}` as MessageKey);
  return humanizeAction(action);
}

function getResourceLabel(t: TFn, type: string): string {
  if (AUDIT_RESOURCES.includes(type)) return t(`audit.resource.${type}` as MessageKey);
  return type;
}

function avatarStyle(name: string): React.CSSProperties {
  let hash = 0;
  for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) >>> 0;
  const hue = hash % 360;
  return {
    background: `linear-gradient(135deg, oklch(0.6 0.15 ${hue}), oklch(0.48 0.14 ${hue}))`,
  };
}

function OnboardingChecklist({
  t,
  onboarding,
  canWrite,
}: {
  t: TFn;
  onboarding: OverviewResponse['onboarding'];
  canWrite: boolean;
}) {
  const navigate = useNavigate();
  const items: {
    key: keyof OverviewResponse['onboarding'];
    labelKey: MessageKey;
    nav?: string;
  }[] = [
    { key: 'hasFlags', labelKey: 'overview.onboarding.hasFlags', nav: canWrite ? '/flags?new=1' : undefined },
    { key: 'hasEnvironments', labelKey: 'overview.onboarding.hasEnvironments', nav: '/settings' },
    { key: 'hasApiKey', labelKey: 'overview.onboarding.hasApiKey', nav: '/apikeys' },
    { key: 'hasConnectedSdk', labelKey: 'overview.onboarding.hasConnectedSdk', nav: '/applications' },
    { key: 'hasTeam', labelKey: 'overview.onboarding.hasTeam', nav: '/users' },
  ];
  const done = items.filter((i) => onboarding[i.key]).length;
  const remaining = items.length - done;
  const subtitle =
    remaining > 0
      ? `Остался ${remaining} ${russianPlural(remaining, 'шаг', 'шага', 'шагов')}`
      : '';

  return (
    <div className="space-y-3">
      <div className="flex items-baseline justify-between">
        <h2 className="text-h3 font-heading font-semibold tracking-tight">{t('overview.onboarding.title')}</h2>
        <span className="text-caption text-muted-foreground">
          {done}/{items.length}
        </span>
      </div>
      {subtitle && <p className="text-caption text-muted-foreground/80 -mt-1">{subtitle}</p>}

      <div className="space-y-0.5">
        {items.map((item) => {
          const isDone = onboarding[item.key];
          const canAct = !isDone && !!item.nav;

          const content = (
            <div
              className={`flex items-center gap-2.5 px-2 py-2 rounded-lg text-body-sm ${canAct ? 'cursor-pointer hover:bg-muted/50 transition-colors' : ''} ${isDone ? 'text-muted-foreground/70' : 'text-foreground'}`}
            >
              <span
                className={`size-[18px] rounded-full border shrink-0 grid place-items-center transition-colors ${
                  isDone ? 'bg-success border-success' : 'border-muted-foreground/30'
                }`}
              >
                {isDone && <Check size={11} className="text-success-foreground" />}
              </span>
              <span className="flex-1">{t(item.labelKey)}</span>
              {canAct && (
                <Plus size={14} className="text-muted-foreground/40 shrink-0" />
              )}
            </div>
          );

          if (canAct) {
            return (
              <button
                key={item.key}
                type="button"
                onClick={() => navigate(item.nav!)}
                className="w-full text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 rounded-lg"
              >
                {content}
              </button>
            );
          }

          return <div key={item.key}>{content}</div>;
        })}
      </div>
    </div>
  );
}

function OverviewSkeleton() {
  return (
    <div className="space-y-6 sm:space-y-8">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <Skeleton className="h-8 w-56" />
        <Skeleton className="h-9 w-32 rounded-lg" />
      </div>
    <div className="grid grid-cols-2 sm:grid-cols-2 lg:grid-cols-4 gap-2 sm:gap-3">
        {Array.from({ length: 4 }).map((_, i) => (
          <Card key={i} padded>
            <Skeleton className="h-3 w-24" />
            <Skeleton className="h-7 w-16 mt-3" />
          </Card>
        ))}
      </div>
      <div className="space-y-4">
        <Skeleton className="h-5 w-40" />
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <Card key={i}>
              <div className="p-4">
                <div className="flex items-center gap-2 mb-4">
                  <Skeleton className="w-6 h-6 rounded-lg" />
                  <Skeleton className="h-4 w-24" />
                </div>
                <div className="flex items-center gap-4">
                  <Skeleton className="w-24 h-24 rounded-full shrink-0" />
                  <div className="flex-1 space-y-2.5">
                    <Skeleton className="h-3 w-full" />
                    <Skeleton className="h-3 w-4/5" />
                    <Skeleton className="h-3 w-3/5" />
                  </div>
                </div>
              </div>
            </Card>
          ))}
        </div>
      </div>
      <div className="space-y-4">
        <Skeleton className="h-5 w-56" />
        <Card>
          <div className="p-4 space-y-3">
            {Array.from({ length: 4 }).map((_, i) => (
              <Skeleton key={i} className="h-6 w-full" />
            ))}
          </div>
        </Card>
      </div>
    </div>
  );
}

function initials(name: string): string {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/);
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[1][0]).toUpperCase();
}

function humanizeAction(action: string): string {
  return action.replace(/[._]/g, ' ');
}
