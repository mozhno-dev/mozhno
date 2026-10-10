import { useState, useEffect, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Plus,
  Mail,
  Shield,
  Clock,
  Trash2,
  Edit2,
  Fingerprint,
  ChevronDown,
  ChevronUp,
  Activity,
  User,
  Check,
  Crown,
  Code2,
  Eye,
  Lock,
} from '@/shared/icons';
import { motion, AnimatePresence } from 'motion/react';
import { toast } from 'sonner';
import { SidePanel } from '@/app/components/SidePanel';
import { TipCard } from '@/app/components/TipCard';
import { ConfirmDialog } from '@/app/components/ConfirmDialog';
import { UserTableSkeleton } from '@/app/components/skeletons';
import { api, UserDto } from '@/api';
import { queryKeys } from '@/api/queryKeys';
import { useProjectQuery } from '@/app/hooks/queries';
import { AuthedAvatarImage } from '@/app/components/AuthedAvatarImage';
import { SectionHeader, GradientButton, EmptyState, SearchInput, ColorIcon, ErrorBox, Badge, getErrorMessage, Fab } from '@/shared';
import { EmptyUsersIllustration } from '@/shared/components/illustrations';
import { useT, useLocale, t } from '@/i18n';
import { loadLocale, toIntlLocale } from '@/i18n/locale';
import { Avatar, AvatarFallback } from '@/app/components/ui/avatar';

function formatDate(d: string) {
  if (!d) return '—';
  return new Date(d).toLocaleDateString(toIntlLocale(loadLocale()), {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });
}

function formatDateTime(d: string) {
  if (!d) return t('users.time.never');
  return new Date(d).toLocaleString(toIntlLocale(loadLocale()), {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function timeAgo(d: string) {
  if (!d) return t('users.time.never');
  const diff = Date.now() - new Date(d).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return t('users.time.justNow');
  if (mins < 60) return t('users.time.minutesAgo', { n: String(mins) });
  const hours = Math.floor(mins / 60);
  if (hours < 24) return t('users.time.hoursAgo', { n: String(hours) });
  const days = Math.floor(hours / 24);
  if (days < 30) return t('users.time.daysAgo', { n: String(days) });
  return formatDate(d);
}

export function Users() {
  const [filter, setFilter] = useState('');
  const [debouncedFilter, setDebouncedFilter] = useState('');
  const [expandedIds, setExpandedIds] = useState<Set<number>>(new Set());
  const [roleFilter, setRoleFilter] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState<string | null>(null);
  const [displayLimit, setDisplayLimit] = useState(10);

  const [isPanelOpen, setIsPanelOpen] = useState(false);
  const [editingUser, setEditingUser] = useState<UserDto | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const [formData, setFormData] = useState({
    email: '',
    role: 'viewer',
    status: 'invited',
  });
  const [initialFormData, setInitialFormData] = useState<typeof formData | null>(null);
  const [deleteId, setDeleteId] = useState<number | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [resettingPassword, setResettingPassword] = useState(false);
  const [resetPasswordOpen, setResetPasswordOpen] = useState(false);
  const [diffOpen, setDiffOpen] = useState(false);
  const t = useT();
  const { locale } = useLocale();
  const { data: project } = useProjectQuery();
  const projectId = project?.id ?? null;

  const {
    data: fetchedUsers,
    isLoading: loading,
    isError: loadError,
    refetch: refetchUsers,
  } = useQuery({
    queryKey: queryKeys.users.all,
    queryFn: () => api.users.list(),
    enabled: !!projectId,
  });

  const users = fetchedUsers ?? [];

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedFilter(filter), 200);
    return () => clearTimeout(timer);
  }, [filter]);

  const toggleExpand = (id: number) => {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const handleOpenCreate = () => {
    setEditingUser(null);
    setError('');
    setFormData({ email: '', role: 'viewer', status: 'invited' });
    setInitialFormData(null);
    setIsPanelOpen(true);
  };

  const handleOpenEdit = (user: UserDto) => {
    setEditingUser(user);
    setError('');
    const initial = {
      email: user.email,
      role: user.role,
      status: user.status,
    };
    setFormData(initial);
    setInitialFormData(initial);
    setIsPanelOpen(true);
  };

  const handleDelete = async () => {
    if (!deleteId) return;
    setDeleting(true);
    try {
      await api.users.delete(deleteId);
      refetchUsers();
      setExpandedIds((prev) => {
        const next = new Set(prev);
        next.delete(deleteId);
        return next;
      });
      setDeleteId(null);
      setIsPanelOpen(false);
    } catch (e: unknown) {
      toast.error(getErrorMessage(e));
    } finally {
      setDeleting(false);
    }
  };

  const handleResetPassword = async () => {
    if (!editingUser) return;
    setResettingPassword(true);
    try {
      await api.users.sendResetLink(editingUser.id);
      toast.success(t('users.form.resetLinkSent'));
      setResetPasswordOpen(false);
    } catch (e: unknown) {
      toast.error(getErrorMessage(e));
    } finally {
      setResettingPassword(false);
    }
  };

  const isDirty = useMemo(() => {
    if (!editingUser || !initialFormData) return false;
    return (
      formData.role !== initialFormData.role ||
      formData.status !== initialFormData.status
    );
  }, [formData, editingUser, initialFormData]);

  const handleSave = async () => {
    setError('');
    if (editingUser && initialFormData && (formData.status !== initialFormData.status || formData.role !== initialFormData.role)) {
      setDiffOpen(true);
      return;
    }
    await doSave();
  };

  const doSave = async () => {
    setSaving(true);
    try {
      if (editingUser) {
        const payload: Record<string, unknown> = {
          role: formData.role,
          status: formData.status,
        };
        await api.users.update(editingUser.id, payload);
        refetchUsers();
      } else {
        await api.users.invite({
          email: formData.email,
          role: formData.role,
          locale,
        });
        toast.success(t('users.form.inviteSent', { email: formData.email }));
        refetchUsers();
      }
      setIsPanelOpen(false);
      setDiffOpen(false);
    } catch (e: unknown) {
      setError(getErrorMessage(e));
    } finally {
      setSaving(false);
    }
  };

  const roleVariantMap: Record<string, 'warning' | 'info' | 'default'> = {
    admin: 'warning',
    developer: 'info',
  };

  const getRoleVariant = (role: string): 'warning' | 'info' | 'default' =>
    roleVariantMap[role] ?? 'default';

  const statusVariantMap: Record<string, 'success' | 'warning' | 'destructive' | 'default'> = {
    active: 'success',
    invited: 'warning',
    suspended: 'destructive',
  };

  const getStatusVariant = (status: string): 'success' | 'warning' | 'destructive' | 'default' =>
    statusVariantMap[status] ?? 'default';

  const getRoleLabel = (role: string) => {
    switch (role) {
      case 'admin':
        return t('users.role.admin');
      case 'developer':
        return t('users.role.developer');
      case 'viewer':
        return t('users.role.viewer');
      default:
        return role;
    }
  };

  const getRoleIcon = (role: string, size: number = 12, className: string = '') => {
    switch (role) {
      case 'admin':
        return <Crown size={size} className={className} />;
      case 'developer':
        return <Code2 size={size} className={className} />;
      case 'viewer':
        return <Eye size={size} className={className} />;
      default:
        return <Shield size={size} className={className} />;
    }
  };

  const getStatusLabel = (status: string) => {
    switch (status) {
      case 'active':
        return t('users.status.active');
      case 'invited':
        return t('users.status.invited');
      case 'suspended':
        return t('users.status.suspended');
      default:
        return status;
    }
  };

  const renderRoleFilterBtn = (role: string, label: string) => {
    const active = roleFilter === role;
    const icon = getRoleIcon(role);
    const style =
      role === 'admin'
        ? {
              on: 'bg-warning/10 text-palette-warning-600 dark:text-palette-warning-700 border-warning/20',
            off: 'bg-accent text-muted-foreground hover:bg-accent/80 border-transparent',
          }
        : role === 'developer'
          ? {
              on: 'bg-info/10 text-palette-info-600 dark:text-palette-info-700 border-info/20',
              off: 'bg-accent text-muted-foreground hover:bg-accent/80 border-transparent',
            }
          : {
              on: 'bg-gradient-to-r from-muted/10 to-muted/10 text-foreground/80 border-border/20',
              off: 'bg-accent text-muted-foreground hover:bg-accent/80 border-transparent',
            };
    return (
      <button
        onClick={() => setRoleFilter(active ? null : role)}
        aria-pressed={active}
        className={`inline-flex items-center gap-1.5 px-3 py-2.5 sm:py-1.5 text-caption font-semibold rounded-lg transition-all border ${active ? style.on : style.off}`}
      >
        {icon}
        {label}
      </button>
    );
  };

  const renderStatusFilterBtn = (status: string, label: string) => {
    const active = statusFilter === status;
    const style =
      status === 'active'
        ? {
            on: 'bg-success/10 text-success dark:text-palette-success-700 border-success/20',
            off: 'bg-accent text-muted-foreground hover:bg-accent/80 border-transparent',
            dot: 'bg-success',
          }
        : status === 'invited'
          ? {
            on: 'bg-warning/10 text-palette-warning-600 dark:text-palette-warning-700 border-warning/20',
              off: 'bg-accent text-muted-foreground hover:bg-accent/80 border-transparent',
              dot: 'bg-warning',
            }
          : {
              on: 'bg-destructive/10 text-destructive dark:text-palette-danger-700 border-destructive/20',
              off: 'bg-accent text-muted-foreground hover:bg-accent/80 border-transparent',
              dot: 'bg-destructive',
            };
    return (
      <button
        onClick={() => setStatusFilter(active ? null : status)}
        aria-pressed={active}
        className={`inline-flex items-center gap-1.5 px-3 py-2.5 sm:py-1.5 text-caption font-semibold rounded-lg transition-all border ${active ? style.on : style.off}`}
      >
        <span className={`w-1.5 h-1.5 rounded-full ${style.dot}`} />
        {label}
      </button>
    );
  };

  let filtered = users;
  if (roleFilter) filtered = filtered.filter((u) => u.role === roleFilter);
  if (statusFilter) filtered = filtered.filter((u) => u.status === statusFilter);
  if (debouncedFilter.trim()) {
    const q = debouncedFilter.toLowerCase();
    filtered = filtered.filter(
      (u) => u.email.toLowerCase().includes(q) || (u.name ?? '').toLowerCase().includes(q),
    );
  }
  const visibleUsers = filtered.slice(0, displayLimit);
  const hasMoreUsers = displayLimit < filtered.length;
  const showMoreUsers = () => setDisplayLimit((prev) => Math.min(prev + 10, filtered.length));
  const showAllUsers = () => setDisplayLimit(filtered.length);

  return (
    <div className="space-y-4 sm:space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <SectionHeader title={t('users.title')} description={t('users.description')} />
        <div className="hidden sm:block">
          <GradientButton onClick={handleOpenCreate} icon={<Plus size={18} />}>
            {t('users.createInvite')}
          </GradientButton>
        </div>
      </div>

      <TipCard
        text={t('users.tipText')}
        label={t('users.tipLabel')}
        icon={<Fingerprint />}
        storageKey="users"
      />

      <div className="flex flex-col sm:flex-row sm:items-center gap-3">
        <SearchInput value={filter} onChange={setFilter} />
        <div className="flex items-center gap-1.5 flex-wrap">
          <button
            onClick={() => setRoleFilter(null)}
            aria-pressed={!roleFilter}
            className={`inline-flex items-center px-3 py-2.5 sm:py-1.5 text-caption font-semibold rounded-lg transition-all border ${
              !roleFilter
                ? 'bg-brand/10 text-brand border-brand/20'
                : 'bg-accent text-muted-foreground hover:bg-accent/80 border-transparent'
            }`}
          >
            {t('users.filterAllRoles')}
          </button>
          {renderRoleFilterBtn('admin', t('users.role.admin'))}
          {renderRoleFilterBtn('developer', t('users.role.developer'))}
          {renderRoleFilterBtn('viewer', t('users.role.viewer'))}
        </div>
        <div className="flex items-center gap-1.5 flex-wrap">
          <button
            onClick={() => setStatusFilter(null)}
            aria-pressed={!statusFilter}
            className={`inline-flex items-center px-3 py-2.5 sm:py-1.5 text-caption font-semibold rounded-lg transition-all border ${
              !statusFilter
                ? 'bg-brand/10 text-brand border-brand/20'
                : 'bg-accent text-muted-foreground hover:bg-accent/80 border-transparent'
            }`}
          >
            {t('users.filterAllStatuses')}
          </button>
          {renderStatusFilterBtn('active', t('users.status.active'))}
          {renderStatusFilterBtn('invited', t('users.status.invited'))}
          {renderStatusFilterBtn('suspended', t('users.status.suspended'))}
        </div>
      </div>

      <div className="space-y-3">
        {loadError ? (
          <ErrorBox>
            {t('users.errors.load')}{' '}
            <button onClick={() => refetchUsers()} className="underline hover:no-underline font-semibold">
              {t('common.retry')}
            </button>
          </ErrorBox>
        ) : loading ? (
          <UserTableSkeleton count={4} />
        ) : filtered.length === 0 ? (
          <EmptyState
            illustration={<EmptyUsersIllustration />}
            title={t('users.emptyTitle')}
            description={
              filter || roleFilter || statusFilter
                ? t('users.emptyFiltered')
                : t('users.emptyDescription')
            }
            buttonLabel={
              !filter && !roleFilter && !statusFilter ? t('users.emptyButton') : undefined
            }
            onAction={!filter && !roleFilter && !statusFilter ? handleOpenCreate : undefined}
          />
        ) : (
          <>
            <AnimatePresence mode="popLayout">
              {visibleUsers.map((user, idx) => {
                const expanded = expandedIds.has(user.id);
                const initials = (user.name ?? user.email).substring(0, 2).toUpperCase();
                const hasAvatar = !!user.avatar;
                const avatarUrl = api.users.getAvatarUrl(user.id);
                return (
                  <motion.div
                    key={user.id}
                    initial={{ opacity: 0, y: 12 }}
                    animate={{ opacity: 1, y: 0 }}
                    exit={{ opacity: 0, x: -20 }}
                    transition={{ duration: 0.2, delay: idx * 0.03 }}
                    className="group bg-card rounded-xl shadow-md hover:shadow-lg transition-shadow duration-200 overflow-hidden"
                    id={`user-card-${user.id}`}
                  >
                    <div
                      role="button"
                      tabIndex={0}
                      aria-expanded={expanded}
                      className="flex gap-4 px-4 py-3 cursor-pointer"
                      onClick={() => toggleExpand(user.id)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' || e.key === ' ') {
                          e.preventDefault();
                          toggleExpand(user.id);
                        }
                      }}
                    >
                      <div className="flex-1 min-w-0 flex items-center gap-3">
                        <div className="flex items-center gap-2 flex-wrap min-w-0">
                          <Avatar className="w-8 h-8 shadow-md shrink-0">
                            <AuthedAvatarImage
                              src={hasAvatar ? avatarUrl : null}
                              alt={user.name ?? ''}
                            />
                            <AvatarFallback className="bg-brand text-caption font-bold text-primary-foreground">
                              {initials}
                            </AvatarFallback>
                          </Avatar>
                          <div className="min-w-0">
                            <span className="font-semibold text-body-sm text-foreground truncate transition-all">
                              {user.name ?? user.email}
                            </span>
                            {user.name && (
                              <div className="text-caption text-muted-foreground truncate flex items-center gap-1">
                                <Mail size={10} />
                                {user.email}
                              </div>
                            )}
                          </div>
                          <Badge variant={getRoleVariant(user.role)} size="sm">
                            {getRoleLabel(user.role)}
                          </Badge>
                          <Badge variant={getStatusVariant(user.status)} size="sm">
                            {getStatusLabel(user.status)}
                          </Badge>
                        </div>
                      </div>
                      <div className="flex items-center gap-3 shrink-0">
                        {!expanded && (
                          <span className="text-caption text-muted-foreground flex items-center gap-1 shrink-0">
                            <Clock size={10} />
                            {timeAgo(user.lastActiveAt)}
                          </span>
                        )}
                        {expanded ? (
                          <ChevronUp
                            size={16}
                            className="text-muted-foreground group-hover:text-brand transition-colors"
                          />
                        ) : (
                          <ChevronDown
                            size={16}
                            className="text-muted-foreground group-hover:text-brand transition-colors"
                          />
                        )}
                      </div>
                    </div>

                    <AnimatePresence initial={false}>
                      {expanded && (
                        <motion.div
                          initial={{ height: 0, opacity: 0 }}
                          animate={{ height: 'auto', opacity: 1 }}
                          exit={{ height: 0, opacity: 0 }}
                          transition={{ duration: 0.25, ease: 'easeInOut' }}
                          className="overflow-hidden"
                        >
                          <div className="border-t border-border">
                            <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 divide-x divide-border">
                              <div className="px-3 py-2.5">
                                <span className="text-caption font-semibold text-muted-foreground/70 block mb-1">
                                  Email
                                </span>
                                <span className="text-caption text-foreground/80 flex items-center gap-1.5 truncate">
                                  <Mail size={11} className="text-muted-foreground shrink-0" />
                                  {user.email}
                                </span>
                              </div>
                              <div className="px-3 py-2.5">
                                <span className="text-caption font-semibold text-muted-foreground/70 block mb-1">
                                  {t('users.card.role')}
                                </span>
                                <span className="text-caption font-medium flex items-center gap-1.5">
                                  {getRoleIcon(user.role, 11, 'text-muted-foreground shrink-0')}
                                  <Badge variant={getRoleVariant(user.role)} size="sm">
                                    {getRoleLabel(user.role)}
                                  </Badge>
                                </span>
                              </div>
                              <div className="px-3 py-2.5">
                                <span className="text-caption font-semibold text-muted-foreground/70 block mb-1">
                                  {t('users.card.status')}
                                </span>
                                <Badge variant={getStatusVariant(user.status)} size="sm">
                                  {getStatusLabel(user.status)}
                                </Badge>
                              </div>
                              <div className="px-3 py-2.5">
                                <span className="text-caption font-semibold text-muted-foreground/70 block mb-1">
                                  {t('users.card.created')}
                                </span>
                                <span className="text-caption text-foreground/80 flex items-center gap-1.5">
                                  <Clock size={11} className="text-muted-foreground shrink-0" />
                                  {formatDate(user.createdAt)}
                                </span>
                              </div>
                              <div className="px-3 py-2.5">
                                <span className="text-caption font-semibold text-muted-foreground/70 block mb-1">
                                  {t('users.card.lastActive')}
                                </span>
                                <span className="text-caption text-foreground/80">
                                  {formatDateTime(user.lastActiveAt)}
                                </span>
                              </div>
                            </div>
                            <div className="flex items-center gap-2 px-4 py-2 border-t border-border">
                              <button
                                onClick={(e) => {
                                  e.stopPropagation();
                                  handleOpenEdit(user);
                                }}
                                className="inline-flex items-center gap-1.5 px-3 py-1.5 text-caption font-medium text-muted-foreground bg-secondary border border-border rounded-xl hover:text-brand hover:border-brand/20 hover:bg-brand/5 transition-all"
                              >
                                <Edit2 size={12} />
                                {t('users.card.edit')}
                              </button>
                              <button
                                onClick={(e) => {
                                  e.stopPropagation();
                                  setDeleteId(user.id);
                                }}
                                className="inline-flex items-center gap-1.5 px-3 py-1.5 text-caption font-medium text-muted-foreground bg-secondary border border-border rounded-xl hover:text-destructive hover:border-destructive/20 hover:bg-destructive/10 transition-all"
                              >
                                <Trash2 size={12} />
                                {t('users.card.delete')}
                              </button>
                            </div>
                          </div>
                        </motion.div>
                      )}
                    </AnimatePresence>
                  </motion.div>
                );
              })}
            </AnimatePresence>
            {hasMoreUsers && (
              <div className="flex items-center justify-center gap-3 pt-3 pb-1">
                <GradientButton variant="secondary" onClick={showMoreUsers}>
                  {t('users.list.showMore', { n: String(filtered.length - visibleUsers.length) })}
                </GradientButton>
                <GradientButton
                  variant="secondary"
                  onClick={showAllUsers}
                  className="bg-brand/10 border-brand/20 text-brand hover:bg-brand/20"
                >
                  {t('users.list.showAll', { n: String(filtered.length) })}
                </GradientButton>
              </div>
            )}
          </>
        )}
      </div>

      <SidePanel
        open={isPanelOpen}
        onOpenChange={setIsPanelOpen}
        diffSlot={
          diffOpen ? (
            <div className="border-t border-border bg-secondary/30 dark:bg-secondary/10">
              <div className="px-6 pt-4 pb-1">
                <span className="text-caption font-semibold text-muted-foreground/70">
                  {t('common.reviewChanges')}
                </span>
              </div>
              <div className="px-6 pb-4 space-y-2.5">
                {initialFormData && formData.role !== initialFormData.role && (
                  <div className="flex items-center gap-3">
                    <span className="text-caption font-medium text-muted-foreground w-12 shrink-0">{t('users.card.role')}</span>
                    <Badge variant={getRoleVariant(initialFormData.role)} size="sm">
                      {getRoleLabel(initialFormData.role)}
                    </Badge>
                    <span className="text-body-sm text-muted-foreground">→</span>
                    <Badge variant={getRoleVariant(formData.role)} size="sm">
                      {getRoleLabel(formData.role)}
                    </Badge>
                  </div>
                )}
                {initialFormData && formData.status !== initialFormData.status && (
                  <div className="flex items-center gap-3">
                    <span className="text-caption font-medium text-muted-foreground w-12 shrink-0">{t('users.card.status')}</span>
                    <Badge variant={getStatusVariant(initialFormData.status)} size="sm">
                      {getStatusLabel(initialFormData.status)}
                    </Badge>
                    <span className="text-body-sm text-muted-foreground">→</span>
                    <Badge variant={getStatusVariant(formData.status)} size="sm">
                      {getStatusLabel(formData.status)}
                    </Badge>
                  </div>
                )}
              </div>
            </div>
          ) : undefined
        }
        onDiffDismiss={diffOpen ? () => setDiffOpen(false) : undefined}
        title={editingUser ? t('users.panel.editTitle') : t('users.panel.createTitle')}
        description={
          editingUser ? t('users.panel.editDescription') : t('users.panel.createDescription')
        }
        footer={
          diffOpen ? (
            <>
              <button
                onClick={() => setDiffOpen(false)}
                className="inline-flex items-center px-5 py-2.5 text-body-sm font-medium text-foreground/80 hover:bg-accent rounded-lg transition-colors"
              >
                {t('common.cancel')}
              </button>
              <GradientButton onClick={doSave} loading={saving}>
                {t('common.applyChanges')}
              </GradientButton>
            </>
          ) : (
            <>
              <button
                onClick={() => setIsPanelOpen(false)}
                className="inline-flex items-center px-5 py-2.5 text-body-sm font-medium text-foreground/80 hover:bg-accent rounded-lg transition-colors"
              >
                {t('common.cancel')}
              </button>
              <GradientButton
                onClick={handleSave}
                disabled={saving || !formData.email || (!!editingUser && !isDirty)}
                loading={saving}
              >
                {editingUser ? t('common.saveChanges') : t('users.panel.invite')}
              </GradientButton>
            </>
          )
        }
      >
        <div className="space-y-5">
            {error && <ErrorBox>{error}</ErrorBox>}

            {editingUser && (
              <div className="flex items-center gap-3 p-4 bg-gradient-to-r from-gradient-subtle-start to-gradient-subtle-end dark:from-brand/5 dark:to-brand/5 border border-info/20 dark:border-brand/20 rounded-xl">
                <Avatar className="w-10 h-10 shadow-md shrink-0">
                  <AuthedAvatarImage
                    src={editingUser.avatar ? api.users.getAvatarUrl(editingUser.id) : null}
                    alt={editingUser.name ?? ''}
                  />
                  <AvatarFallback className="bg-brand text-body-sm font-bold text-primary-foreground">
                    {(editingUser.name ?? editingUser.email).substring(0, 2).toUpperCase()}
                  </AvatarFallback>
                </Avatar>
                <div className="min-w-0">
                  <div className="text-body-sm font-semibold text-foreground truncate">
                    {editingUser.name ?? editingUser.email}
                  </div>
                  <div className="text-caption text-muted-foreground flex items-center gap-1 truncate">
                    <Mail size={11} />
                    {editingUser.email}
                  </div>
                </div>
              </div>
            )}

            {editingUser && (
              <div className="space-y-1.5">
                <label className="text-body-sm font-medium text-foreground/80 flex items-center justify-between">
                  <span>{t('users.form.nameLabel')}</span>
                </label>
                <div className="w-full bg-muted/50 border border-border rounded-lg px-4 py-2.5 text-body-sm font-medium text-muted-foreground flex items-center gap-2 select-text">
                  <span className="flex-1 truncate">{editingUser.name || '—'}</span>
                  <Lock size={14} className="shrink-0 text-muted-foreground/60" />
                </div>
              </div>
            )}

            <div className="space-y-1.5">
              <label className="text-body-sm font-medium text-foreground/80 flex items-center justify-between">
                <span>Email</span>
                {!editingUser && (
                  <span className="text-caption font-normal text-muted-foreground/50 tabular-nums">
                    {formData.email.length}/254
                  </span>
                )}
              </label>
              {editingUser ? (
                <div className="w-full bg-muted/50 border border-border rounded-lg px-4 py-2.5 text-body-sm font-medium text-muted-foreground flex items-center gap-2 select-text">
                  <span className="flex-1 truncate">{editingUser.email}</span>
                  <Lock size={14} className="shrink-0 text-muted-foreground/60" />
                </div>
              ) : (
                <input
                  type="email"
                  value={formData.email}
                  onChange={(e) => setFormData({ ...formData, email: e.target.value })}
                  maxLength={254}
                  placeholder="email@company.com"
                  className="w-full bg-input-background border border-border rounded-lg px-4 py-2.5 text-body-sm font-medium focus:outline-none focus:ring-2 focus:ring-ring/50 focus:border-ring transition-all placeholder:font-normal placeholder:text-muted-foreground"
                />
              )}
            </div>

            {editingUser && (
              <div className="pt-4 border-t border-border">
                <GradientButton
                  type="button"
                  variant="muted"
                  size="md"
                  icon={<Mail size={14} />}
                  onClick={() => setResetPasswordOpen(true)}
                >
                  {t('users.form.sendResetLink')}
                </GradientButton>
              </div>
            )}

            <div className="pt-4 border-t border-border space-y-3">
              <div className="flex items-center gap-2">
                <Shield size={16} className="text-brand" />
                <label className="text-body-sm font-medium text-foreground/80">
                  {t('users.form.roleLabel')}
                </label>
                <span className="inline-flex items-center text-caption px-1.5 py-1 rounded bg-brand/10 text-brand font-medium leading-none">
                  {t('users.form.roleSelect')}
                </span>
              </div>

              <div className="grid grid-cols-1 gap-2">
                {(
                  [
                    {
                      value: 'admin',
                      colorHex: '#c08140',
                      borderColor: 'border-warning',
                      bgHover: 'group-hover:bg-warning/10',
                      bgSelected: 'bg-warning/10',
                      textSelected: 'text-palette-warning-600 dark:text-palette-warning-700',
                      description: t('users.roleDescriptions.admin'),
                    },
                    {
                      value: 'developer',
                      colorHex: '#2d9484',
                      borderColor: 'border-brand',
                      bgHover: 'group-hover:bg-brand/10',
                      bgSelected: 'bg-brand/10',
                      textSelected: 'text-brand',
                      description: t('users.roleDescriptions.developer'),
                    },
                    {
                      value: 'viewer',
                      colorHex: '#5a7260',
                      borderColor: 'border-border',
                      bgHover: 'group-hover:bg-secondary dark:group-hover:bg-muted/10',
                      bgSelected: 'bg-secondary dark:bg-muted/10',
                      textSelected: 'text-foreground/80',
                      description: t('users.roleDescriptions.viewer'),
                    },
                  ] as const
                ).map(({ value, colorHex, borderColor, bgSelected, textSelected, description }) => {
                  const selected = formData.role === value;
                  return (
                    <button
                      key={value}
                      type="button"
                      onClick={() => setFormData({ ...formData, role: value })}
                      className={`group flex items-center gap-3 px-4 py-3 rounded-lg border-2 transition-all text-left ${
                        selected
                          ? `${borderColor} ${bgSelected} ${textSelected} shadow-sm`
                          : 'border-border text-muted-foreground hover:border-border'
                      }`}
                    >
                      <ColorIcon
                        variant="gradient"
                        size="md"
                        color={colorHex}
                        icon={getRoleIcon(value, 16)}
                        shadow
                      />
                      <div className="flex-1 min-w-0">
                        <div
                          className={`text-body-sm font-semibold ${selected ? textSelected : 'text-foreground/80'}`}
                        >
                          {getRoleLabel(value)}
                        </div>
                        <div className="text-caption text-muted-foreground mt-0.5">{description}</div>
                      </div>
                      {selected && (
                        <ColorIcon
                          variant="gradient"
                          size="sm"
                          color={colorHex}
                          icon={<Check size={12} className="text-primary-foreground" strokeWidth={3} />}
                        />
                      )}
                    </button>
                  );
                })}
              </div>
            </div>

            {editingUser && (
              <div className="pt-4 border-t border-border space-y-3">
                <div className="flex items-center gap-2">
                  <Activity size={16} className="text-brand" />
                  <label className="text-body-sm font-medium text-foreground/80">
                    {t('users.form.statusLabel')}
                  </label>
                </div>

                <div className="grid grid-cols-1 gap-2">
                  {(
                    [
                      {
                        value: 'active',
                        label: t('users.status.active'),
                        dotClass: 'bg-success',
                        color: 'success',
                        description: t('users.statusDescriptions.active'),
                      },
                      {
                        value: 'suspended',
                        label: t('users.status.suspended'),
                        dotClass: 'bg-destructive',
                        color: 'destructive',
                        description: t('users.statusDescriptions.suspended'),
                      },
                    ] as const
                  ).map(({ value, label, dotClass, color, description }) => {
                    const selected = formData.status === value;
                    const borderColor = selected ? `border-${color}` : 'border-border';
                    const bgSelected = selected ? `bg-${color}/10` : '';
                    const textSelected = selected ? `text-${color}` : '';
                    return (
                      <button
                        key={value}
                        type="button"
                        onClick={() => setFormData({ ...formData, status: value })}
                        className={`group flex items-center gap-3 px-4 py-3 rounded-lg border-2 transition-all text-left ${
                          selected
                            ? `${borderColor} ${bgSelected} ${textSelected} shadow-sm`
                            : 'border-border text-muted-foreground hover:border-border'
                        }`}
                      >
                        <div
                          className={`w-5 h-5 rounded-full ${dotClass} shrink-0 ring-4 ${selected ? 'ring-border' : 'ring-transparent'} transition-all`}
                        />
                        <div className="flex-1 min-w-0">
                          <div
                            className={`text-body-sm font-semibold ${selected ? textSelected : 'text-foreground/80'}`}
                          >
                            {label}
                          </div>
                          <div className="text-caption text-muted-foreground mt-0.5">{description}</div>
                        </div>
                        {selected && (
                          <div
                            className={`w-5 h-5 rounded-md bg-${color} flex items-center justify-center shrink-0`}
                          >
                            <Check size={12} className="text-primary-foreground" strokeWidth={3} />
                          </div>
                        )}
                      </button>
                    );
                  })}
                </div>
              </div>
            )}

            {editingUser && (
              <div className="pt-4 border-t border-border space-y-2.5">
                <div className="flex items-center gap-2 text-caption text-muted-foreground">
                  <User size={12} className="text-muted-foreground" />
                  <span>
                    {t('users.panel.created')}{' '}
                    <span className="font-medium text-foreground/80">
                      {formatDate(editingUser.createdAt)}
                    </span>
                  </span>
                </div>
                <div className="flex items-center gap-2 text-caption text-muted-foreground">
                  <Clock size={12} className="text-muted-foreground" />
                  <span>
                    {t('users.panel.lastActive')}{' '}
                    <span className="font-medium text-foreground/80">
                      {formatDateTime(editingUser.lastActiveAt)}
                    </span>
                  </span>
                </div>
              </div>
            )}

            <div className="p-4 bg-brand/5 border border-brand/20 rounded-lg">
              <div className="flex gap-3">
                <div className="shrink-0 mt-0.5">
                  <div className="w-5 h-5 rounded-full bg-brand flex items-center justify-center">
                    <Shield size={12} className="text-primary-foreground" />
                  </div>
                </div>
                <div>
                  <h5 className="text-caption font-semibold text-brand mb-1">{t('users.help.title')}</h5>
                  <p className="text-caption text-foreground/70">
                    {t('users.help.admin')}
                    <br />
                    {t('users.help.developer')}
                    <br />
                    {t('users.help.viewer')}
                  </p>
                </div>
              </div>
            </div>

            {editingUser && (
              <div className="pt-6 border-t border-border space-y-3">
                <button
                  type="button"
                  onClick={() => {
                    setDeleteId(editingUser.id);
                  }}
                  className="w-full flex items-center justify-center gap-2 px-4 py-2.5 text-body-sm font-medium text-destructive hover:bg-destructive/10 rounded-lg border border-destructive/20 transition-all"
                >
                  <Trash2 size={16} />
                  {t('users.panel.deleteUser')}
                </button>
              </div>
            )}
          </div>
      </SidePanel>

      <ConfirmDialog
        open={!!deleteId}
        onOpenChange={(open) => {
          if (!open) setDeleteId(null);
        }}
        title={t('users.delete.confirmTitle')}
        description={t('users.delete.confirmDescription', {
          name:
            users.find((u) => u.id === deleteId)?.name ??
            users.find((u) => u.id === deleteId)?.email ??
            '',
        })}
        confirmLabel={t('common.delete')}
        confirmPhrase={
          users.find((u) => u.id === deleteId)?.name ??
          users.find((u) => u.id === deleteId)?.email
        }
        onConfirm={handleDelete}
        loading={deleting}
      />

      <ConfirmDialog
        open={resetPasswordOpen}
        onOpenChange={(open) => {
          if (!open) setResetPasswordOpen(false);
        }}
        title={t('users.resetConfirm.title')}
        description={t('users.resetConfirm.description', {
          name: editingUser?.name ?? editingUser?.email ?? '',
        })}
        confirmLabel={t('users.resetConfirm.send')}
        variant="default"
        onConfirm={handleResetPassword}
        loading={resettingPassword}
      />
      <Fab onClick={handleOpenCreate} label={t('users.createInvite')} />
    </div>
  );
}
