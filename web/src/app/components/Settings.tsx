import React, { useState, useMemo, useRef, useEffect } from 'react';
import {
  AlertTriangle,
  Building2,
  Globe,
  Save,
  Plus,
  Upload,
  Image,
  Hash,
  Clock,
  Trash2,
  Settings as SettingsIcon,
  ShieldCheck,
  Zap,
  Key,
} from '@/shared/icons';
import { motion, AnimatePresence } from 'motion/react';
import { toast } from 'sonner';
import { api, Environment } from '@/api';
import { ConfirmDialog } from '@/app/components/ConfirmDialog';
import { PluginSlot } from '@/app/components/PluginSlot';
import { SectionHeader, EmptyState, GradientButton, LoadingState, ColorPicker, Badge, getEnvColor, getErrorMessage } from '@/shared';
import { EmptySettingsIllustration } from '@/shared/components/illustrations';
import { SidePanel } from '@/app/components/SidePanel';
import { Switch } from '@/app/components/ui/switch';
import { useProjectQuery, useEnvironmentsQuery } from '@/app/hooks/queries';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { queryKeys } from '@/api/queryKeys';
import { useLocale, useT } from '@/i18n';
import { useAuth } from '@/app/auth/useAuth';
import { toIntlLocale } from '@/i18n/locale';
import { useProjectLogo } from '@/app/hooks/queries';

function formatDate(iso: string, locale: string): string {
  return new Date(iso).toLocaleDateString(locale, {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  });
}

export function Settings() {
  const queryClient = useQueryClient();
  const { locale } = useLocale();
  const t = useT();
  const { user } = useAuth();
  const intlLocale = toIntlLocale(locale);

  const { data: project, isLoading: projectLoading } = useProjectQuery();
  const projectId = project?.id ?? null;
  const projectLogoUrl = useProjectLogo(project?.logo ?? null);

  const { data: environments = [] } = useEnvironmentsQuery();

  const { data: apiKeys = [] } = useQuery({
    queryKey: queryKeys.apiKeys.byProject(projectId),
    queryFn: () => api.apiKeys.list(),
    enabled: !!projectId,
    staleTime: 30_000,
  });

  const apiKeysByEnv = useMemo(() => {
    const map: Record<number, number> = {};
    for (const k of apiKeys) {
      if (k.environmentId != null) {
        map[k.environmentId] = (map[k.environmentId] || 0) + 1;
      }
    }
    return map;
  }, [apiKeys]);

  useQuery({
    queryKey: queryKeys.settings.byProject(projectId),
    queryFn: () => api.settings.get(),
    enabled: !!projectId,
    staleTime: 5 * 60_000,
  });

  const { data: envLimitData } = useQuery({
    queryKey: queryKeys.environments.limit(projectId),
    queryFn: () => api.environments.getLimit(),
    enabled: !!projectId,
    staleTime: 5 * 60_000,
    retry: 0,
  });

  const loading = projectLoading;

  const [projectName, setProjectName] = useState('');
  const [projectDesc, setProjectDesc] = useState('');
  const [initialProject, setInitialProject] = useState<{ name: string; desc: string } | null>(null);
  const [savingProject, setSavingProject] = useState(false);

  const [envPanelOpen, setEnvPanelOpen] = useState(false);
  const [editingEnv, setEditingEnv] = useState<Environment | null>(null);
  const [envFormName, setEnvFormName] = useState('');
  const [envFormDesc, setEnvFormDesc] = useState('');
  const [envFormColor, setEnvFormColor] = useState('#2d9484');
  const [envFormApproval, setEnvFormApproval] = useState(false);
  const [savingEnvEdit, setSavingEnvEdit] = useState(false);
  const [newEnvName, setNewEnvName] = useState('');
  const [savingEnv, setSavingEnv] = useState(false);
  const [deleteEnvId, setDeleteEnvId] = useState<number | null>(null);
  const [deletingEnv, setDeletingEnv] = useState(false);

  const [uploadingLogo, setUploadingLogo] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [pendingLogoFile, setPendingLogoFile] = useState<File | null>(null);
  const [pendingLogoPreviewUrl, setPendingLogoPreviewUrl] = useState<string | null>(null);

  const maxEnvironments = envLimitData?.maxEnvironments;

  useEffect(() => {
    if (project) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setProjectName(project.name);
      setProjectDesc(project.description ?? '');
      setInitialProject((prev) => prev ?? { name: project.name, desc: project.description ?? '' });
    }
  }, [project]);

  useEffect(() => {
    return () => {
      if (pendingLogoPreviewUrl) URL.revokeObjectURL(pendingLogoPreviewUrl);
    };
  }, [pendingLogoPreviewUrl]);

  const openEnvPanel = (env: Environment) => {
    setEditingEnv(env);
    setEnvFormName(env.name);
    setEnvFormDesc(env.description ?? '');
    setEnvFormColor(getEnvColor(env));
    setEnvFormApproval(env.requireActivationApproval ?? false);
    setEnvPanelOpen(true);
  };

  const closeEnvPanel = () => {
    setEnvPanelOpen(false);
    setEditingEnv(null);
    setEnvFormName('');
    setEnvFormDesc('');
    setEnvFormColor('#2d9484');
    setEnvFormApproval(false);
  };

  const saveEditEnvMutation = useMutation({
    mutationFn: () =>
      api.environments.update(editingEnv!.id, {
        name: envFormName.trim(),
        description: envFormDesc.trim() || null,
        color: envFormColor,
        requireActivationApproval: envFormApproval,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.flags.enriched });
      queryClient.invalidateQueries({ queryKey: queryKeys.environments.all });
      closeEnvPanel();
    },
    onError: (e: unknown) => {
      toast.error(getErrorMessage(e));
    },
    onSettled: () => setSavingEnvEdit(false),
  });

  const handleSaveEnv = () => {
    if (!projectId || !editingEnv || !envFormName.trim()) return;
    setSavingEnvEdit(true);
    saveEditEnvMutation.mutate();
  };

  const saveProjectMutation = useMutation({
    mutationFn: async () => {
      if (!projectId) throw new Error('No project');
      if (pendingLogoFile) {
        setUploadingLogo(true);
        await api.projects.uploadLogo(pendingLogoFile);
        setUploadingLogo(false);
      }
      return api.projects.update({ name: projectName, description: projectDesc });
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.projects.all }),
        queryClient.invalidateQueries({ queryKey: queryKeys.environments.all }),
      ]);
      setInitialProject({ name: projectName, desc: projectDesc });
      window.dispatchEvent(new Event('project-updated'));
      if (pendingLogoPreviewUrl) URL.revokeObjectURL(pendingLogoPreviewUrl);
      setPendingLogoFile(null);
      setPendingLogoPreviewUrl(null);
    },
    onError: (e: unknown) => {
      toast.error(getErrorMessage(e));
    },
    onSettled: () => setSavingProject(false),
  });

  const isProjectDirty = useMemo(() => {
    if (!initialProject || !project) return false;
    return projectName !== initialProject.name || projectDesc !== initialProject.desc || !!pendingLogoFile;
  }, [projectName, projectDesc, pendingLogoFile, project, initialProject]);

  const saveProject = () => {
    if (!projectId) return;
    setSavingProject(true);
    saveProjectMutation.mutate();
  };

  const handleLogoUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (pendingLogoPreviewUrl) URL.revokeObjectURL(pendingLogoPreviewUrl);
    setPendingLogoFile(file);
    setPendingLogoPreviewUrl(URL.createObjectURL(file));
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const addEnvMutation = useMutation({
    mutationFn: () => api.environments.create({ name: newEnvName.trim() }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.flags.enriched });
      queryClient.invalidateQueries({ queryKey: queryKeys.environments.all });
      setNewEnvName('');
    },
    onError: (e: unknown) => {
      toast.error(getErrorMessage(e));
    },
    onSettled: () => setSavingEnv(false),
  });

  const addEnv = () => {
    if (!projectId || !newEnvName.trim()) return;
    if (maxEnvironments != null && environments.length >= maxEnvironments) {
      toast.error(t('settings.errorMaxEnv', { max: String(maxEnvironments) }));
      return;
    }
    setSavingEnv(true);
    addEnvMutation.mutate();
  };

  const removeEnvMutation = useMutation({
    mutationFn: () => api.environments.delete(deleteEnvId!),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.flags.enriched });
      queryClient.invalidateQueries({ queryKey: queryKeys.environments.all });
      if (editingEnv?.id === deleteEnvId) {
        closeEnvPanel();
      }
      setDeleteEnvId(null);
    },
    onError: (e: unknown) => {
      toast.error(getErrorMessage(e));
    },
    onSettled: () => setDeletingEnv(false),
  });

  const removeEnv = () => {
    if (!projectId || !deleteEnvId) return;
    setDeletingEnv(true);
    removeEnvMutation.mutate();
  };

  const [resetOpen, setResetOpen] = useState(false);
  const [resettingProject, setResettingProject] = useState(false);

  const resetProjectMutation = useMutation({
    mutationFn: () => api.projects.reset(),
    onSuccess: () => {
      toast.success(t('settings.projectReset'));
      setResetOpen(false);
      queryClient.invalidateQueries({ queryKey: queryKeys.flags.enriched });
      queryClient.invalidateQueries({ queryKey: queryKeys.environments.all });
      queryClient.invalidateQueries({ queryKey: queryKeys.apiKeys.byProject(projectId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.contexts.all });
      queryClient.invalidateQueries({ queryKey: queryKeys.segments.all });
    },
    onError: (e: unknown) => {
      toast.error(getErrorMessage(e));
    },
    onSettled: () => setResettingProject(false),
  });

  if (loading) return <LoadingState text={t('settings.loading')} />;

  return (
    <div className="space-y-4 sm:space-y-6">
      <SectionHeader title={t('settings.title')} description={t('settings.description')} />

      <div className="space-y-6">
        {/* Project Card */}
        <motion.div
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.2 }}
          className="bg-card rounded-xl shadow-md overflow-hidden"
        >
          <div className="p-4 sm:p-6">
            <div className="flex items-center gap-3 mb-5">
              <div className="p-2.5 rounded-xl bg-muted border border-border">
                <Building2 size={20} className="text-info" />
              </div>
              <div>
                <h2 className="text-h2 font-semibold text-foreground">{t('settings.project')}</h2>
                <p className="text-body-sm text-muted-foreground">{t('settings.projectDescription')}</p>
              </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 divide-y sm:divide-y-0 sm:divide-x divide-border bg-secondary rounded-xl mb-5">
              <div className="px-4 py-2.5">
                <span className="text-caption font-semibold text-muted-foreground/70 block mb-0.5">
                  ID
                </span>
                <span className="text-caption text-foreground/80 flex items-center gap-1.5">
                  <Hash size={11} className="text-muted-foreground shrink-0" />
                  {project?.id}
                </span>
              </div>
              {project?.createdAt && (
                <div className="px-4 py-2.5">
                  <span className="text-caption font-semibold text-muted-foreground/70 block mb-0.5">
                    {t('apiKeys.created')}
                  </span>
                  <span className="text-caption text-foreground/80 flex items-center gap-1.5">
                    <Clock size={11} className="text-muted-foreground shrink-0" />
                    {formatDate(project.createdAt, intlLocale)}
                  </span>
                </div>
              )}
            </div>

            <div className="space-y-5">
              <div className="space-y-1.5">
                <label className="text-body-sm font-medium text-foreground/80 flex items-center justify-between">
                  <span>{t('settings.projectName')}</span>
                  <span className="text-caption font-normal text-muted-foreground/50 tabular-nums">
                    {projectName.length}/120
                  </span>
                </label>
                <input
                  type="text"
                  value={projectName}
                  onChange={(e) => setProjectName(e.target.value)}
                  maxLength={120}
                  placeholder={t('settings.projectNamePlaceholder')}
                  className="w-full bg-input-background border border-border text-foreground rounded-lg px-4 py-2.5 text-body-sm focus:outline-none focus:ring-2 focus:ring-ring/50 focus:border-ring transition-all placeholder:text-muted-foreground"
                />
              </div>

              <div className="space-y-1.5">
                <label className="text-body-sm font-medium text-foreground/80 flex items-center justify-between">
                  <span>{t('settings.descriptionField')}</span>
                  <span className="text-caption font-normal text-muted-foreground/50 tabular-nums">
                    {projectDesc.length}/160
                  </span>
                </label>
                <textarea
                  value={projectDesc}
                  onChange={(e) => setProjectDesc(e.target.value)}
                  maxLength={160}
                  rows={2}
                  placeholder={t('settings.descriptionPlaceholder')}
                  className="w-full bg-input-background border border-border text-foreground rounded-lg px-4 py-2.5 text-body-sm focus:outline-none focus:ring-2 focus:ring-ring/50 focus:border-ring transition-all placeholder:text-muted-foreground resize-none"
                />
              </div>

              <div className="space-y-1.5">
                <label className="text-body-sm font-medium text-foreground/80">
                  {t('settings.logo')}
                </label>
                <div className="flex items-center gap-4 p-4 bg-secondary rounded-xl border border-border">
                  {pendingLogoPreviewUrl || projectLogoUrl ? (
                    <img
                      key={pendingLogoPreviewUrl ?? project?.logo}
                      src={
                        pendingLogoPreviewUrl ||
                        projectLogoUrl ||
                        ''
                      }
                      alt={t('settings.logoAlt')}
                      className="w-16 h-16 rounded-xl object-cover border border-border shadow-sm shrink-0"
                    />
                  ) : (
                    <div className="w-16 h-16 rounded-xl bg-muted border border-border flex items-center justify-center shrink-0">
                      <Image size={24} className="text-warning" />
                    </div>
                  )}
                  <div>
                    <input
                      ref={fileInputRef}
                      type="file"
                      accept="image/png,image/jpeg,image/gif,image/webp"
                      onChange={handleLogoUpload}
                      className="hidden"
                    />
                    <button
                      onClick={() => fileInputRef.current?.click()}
                      disabled={uploadingLogo}
                      className="inline-flex items-center gap-2 px-4 py-2 text-body-sm font-medium text-foreground/80 bg-card border border-border rounded-xl hover:bg-secondary hover:border-border transition-all disabled:opacity-50 active:scale-95"
                    >
                      <Upload size={14} />
                      {pendingLogoFile
                        ? t('settings.logoSelected')
                        : project?.logo
                          ? t('settings.logoReplace')
                          : t('settings.logoUpload')}
                    </button>
                    <p className="text-caption text-muted-foreground/80 mt-1.5">
                      {t('settings.logoHint')}
                    </p>
                  </div>
                </div>
              </div>

              <div className="pt-2">
                <GradientButton
                  onClick={saveProject}
                  disabled={savingProject || !isProjectDirty}
                  loading={savingProject}
                  icon={<Save size={16} />}
                  className="min-h-[44px] sm:min-h-0 sm:h-9"
                >
                  {t('common.saveChanges')}
                </GradientButton>
              </div>
            </div>
          </div>
        </motion.div>

        {/* Environments Card */}
        <motion.div
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.2, delay: 0.1 }}
          className="bg-card rounded-xl shadow-md overflow-hidden"
        >
          <div className="p-4 sm:p-6">
            <div className="flex items-center gap-3 mb-5">
              <div className="p-2.5 rounded-xl bg-muted border border-border">
                <Globe size={20} className="text-brand" />
              </div>
              <div>
                <h2 className="text-h2 font-semibold text-foreground">
                  {t('settings.environments')}
                </h2>
                <p className="text-body-sm text-muted-foreground">
                  {t('settings.environmentsDescription')}
                </p>
              </div>
            </div>

            <div className="space-y-4">
              {maxEnvironments != null && environments.length >= maxEnvironments ? (
                <div className="p-3 bg-warning/10 border border-warning/20 rounded-xl text-body-sm text-palette-warning-600 dark:text-palette-warning-700">
                  {t('settings.envLimitReached', {
                    count: String(maxEnvironments),
                    max: String(maxEnvironments),
                  })}
                </div>
              ) : (
                <div className="space-y-1.5">
                  <label className="text-body-sm font-medium text-foreground/80 flex items-center justify-between">
                    <span>{t('settings.addEnvPlaceholder')}</span>
                    <span className="text-caption font-normal text-muted-foreground/50 tabular-nums">
                      {newEnvName.length}/120
                    </span>
                  </label>
                  <div className="flex items-center gap-2">
                    <input
                      type="text"
                      value={newEnvName}
                      onChange={(e) => setNewEnvName(e.target.value)}
                      maxLength={120}
                      onKeyDown={(e) => e.key === 'Enter' && addEnv()}
                      placeholder={t('settings.addEnvPlaceholder')}
                      className="flex-1 bg-input-background border border-border text-foreground rounded-lg px-4 py-2.5 text-body-sm focus:outline-none focus:ring-2 focus:ring-ring/50 focus:border-ring transition-all placeholder:text-muted-foreground"
                    />
                    <GradientButton
                      onClick={addEnv}
                      disabled={savingEnv || !newEnvName.trim()}
                      loading={savingEnv}
                      icon={<Plus size={16} strokeWidth={2.5} />}
                      className="shrink-0"
                    >
                      {t('common.add')}
                    </GradientButton>
                  </div>
                </div>
              )}

              {maxEnvironments != null && (
                <span className="text-caption font-medium text-muted-foreground">
                  {t('settings.envCount', {
                    count: String(environments.length),
                    max: String(maxEnvironments),
                  })}
                </span>
              )}

              {environments.length > 0 ? (
                <div className="grid gap-3 [grid-template-columns:repeat(auto-fit,minmax(240px,1fr))]">
                  <AnimatePresence mode="popLayout">
                    {environments.map((env, idx) => {
                      const color = getEnvColor(env);
                      const approval = env.requireActivationApproval ?? false;
                      return (
                        <motion.div
                          key={env.id}
                          layout
                          initial={{ opacity: 0, y: 12 }}
                          animate={{ opacity: 1, y: 0 }}
                          exit={{ opacity: 0, scale: 0.96 }}
                          transition={{ duration: 0.2, delay: idx * 0.03 }}
                          className="group bg-card ring-1 ring-border rounded-xl shadow-sm hover:shadow-md hover:ring-border/80 transition-all duration-200 overflow-hidden flex flex-col"
                        >
                          <span aria-hidden className="h-1.5 w-full shrink-0" style={{ backgroundColor: color }} />
                          <div className="p-3.5 flex flex-col gap-2 flex-1 min-w-0">
                            <div className="flex items-center justify-between gap-2">
                              <div className="flex items-center gap-2 min-w-0">
                                <span
                                  aria-hidden
                                  className={`w-2 h-2 rounded-full shrink-0 ${approval ? 'bg-warning' : 'bg-muted-foreground'}`}
                                />
                                <span className="text-body-sm font-semibold text-foreground truncate">
                                  {env.name}
                                </span>
                              </div>
                              <button
                                onClick={(e) => {
                                  e.stopPropagation();
                                  openEnvPanel(env);
                                }}
                                aria-label={t('settings.editEnvTitle', { name: env.name })}
                                title={t('common.edit')}
                                className="-mr-1 p-1.5 rounded-lg text-muted-foreground hover:text-brand hover:bg-accent transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 shrink-0"
                              >
                                <SettingsIcon size={16} />
                              </button>
                            </div>
                            {env.description && (
                              <div className="text-caption text-muted-foreground line-clamp-2">
                                {env.description}
                              </div>
                            )}
                            {(apiKeysByEnv[env.id] || 0) > 0 && (
                              <div className="text-caption font-medium text-muted-foreground/70 flex items-center gap-1">
                                <Key size={11} className="shrink-0" />
                                {t('settings.envApiKeyCount', { count: String(apiKeysByEnv[env.id] || 0) })}
                              </div>
                            )}
                            <div className="mt-auto pt-0.5">
                              {approval ? (
                                <Badge variant="warning" style="subtle" size="sm" icon={<ShieldCheck size={11} />}>
                                  {t('settings.envApprovalBadgeOn')}
                                </Badge>
                              ) : (
                                <Badge variant="default" style="subtle" size="sm" icon={<Zap size={11} />}>
                                  {t('settings.envApprovalBadgeOff')}
                                </Badge>
                              )}
                            </div>
                          </div>
                        </motion.div>
                      );
                    })}
                  </AnimatePresence>
                </div>
              ) : (
                <EmptyState
                  illustration={<EmptySettingsIllustration />}
                  title={t('settings.noEnvs')}
                  description={t('settings.noEnvsDescription')}
                />
              )}
            </div>
          </div>
        </motion.div>

        {/* Danger Zone Card */}
        {user?.role === 'admin' && (
          <motion.div
            initial={{ opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.2, delay: 0.2 }}
            className="bg-card border border-destructive/20 rounded-xl shadow-md overflow-hidden"
          >
            <div className="p-4 sm:p-6">
              <div className="flex items-center gap-3 mb-5">
                <div className="p-2.5 rounded-xl bg-destructive/10 border border-destructive/20">
                  <AlertTriangle size={20} className="text-destructive" />
                </div>
                <div>
                  <h2 className="text-h2 font-semibold text-foreground">
                    {t('settings.dangerZone')}
                  </h2>
                  <p className="text-body-sm text-muted-foreground">
                    {t('settings.dangerZoneDescription')}
                  </p>
                </div>
              </div>

              <div className="flex items-center justify-between gap-4 flex-wrap">
                <div className="min-w-0">
                  <span className="text-body-sm font-medium text-foreground block">
                    {t('settings.resetProject')}
                  </span>
                  <span className="text-body-sm text-muted-foreground block mt-0.5">
                    {t('settings.resetDescription')}
                  </span>
                </div>
                <GradientButton
                  variant="danger"
                  onClick={() => setResetOpen(true)}
                  loading={resettingProject}
                  icon={<Trash2 size={16} />}
                >
                  {t('settings.resetProject')}
                </GradientButton>
              </div>
            </div>
          </motion.div>
        )}
      </div>

      <SidePanel
        open={envPanelOpen}
        onOpenChange={(open) => {
          if (!open) closeEnvPanel();
        }}
        title={t('settings.editEnvTitle', { name: editingEnv?.name ?? '' })}
        description={t('settings.editEnvDescription')}
        footer={
          <>
            {(() => {
              const linkedKeys = editingEnv ? (apiKeysByEnv[editingEnv.id] || 0) : 0;
              return (
                <button
                  onClick={() => {
                    if (editingEnv) setDeleteEnvId(editingEnv.id);
                  }}
                  disabled={linkedKeys > 0}
                  className={`mr-auto inline-flex items-center gap-1.5 px-3 py-2.5 text-body-sm font-medium rounded-lg transition-colors ${
                    linkedKeys > 0
                      ? 'text-muted-foreground/30 cursor-not-allowed'
                      : 'text-destructive hover:bg-destructive/10'
                  }`}
                >
                  <Trash2 size={16} />
                  {t('common.delete')}
                </button>
              );
            })()}
            <button
              onClick={closeEnvPanel}
              className="inline-flex items-center px-5 py-2.5 text-body-sm font-medium text-foreground/80 hover:bg-accent rounded-lg transition-colors"
            >
              {t('common.cancel')}
            </button>
            <GradientButton
              onClick={handleSaveEnv}
              disabled={savingEnvEdit || !envFormName.trim()}
              loading={savingEnvEdit}
            >
              {t('common.saveChanges')}
            </GradientButton>
          </>
        }
      >
        <div className="space-y-6">
          <div className="space-y-1.5">
            <label className="text-body-sm font-medium text-foreground/80 flex items-center justify-between">
              <span>{t('settings.editEnvName')}</span>
              <span className="text-caption font-normal text-muted-foreground/50 tabular-nums">
                {envFormName.length}/120
              </span>
            </label>
            <input
              type="text"
              value={envFormName}
              onChange={(e) => setEnvFormName(e.target.value)}
              maxLength={120}
              placeholder={t('settings.addEnvPlaceholder')}
              autoFocus
              className="w-full bg-input-background border border-border rounded-lg px-4 py-2.5 text-body-sm focus:outline-none focus:ring-2 focus:ring-ring/50 focus:border-ring transition-all placeholder:text-muted-foreground"
            />
          </div>

          <div className="space-y-1.5">
            <label className="text-body-sm font-medium text-foreground/80 flex items-center justify-between">
              <span>{t('settings.envDescription')}</span>
              <span className="text-caption font-normal text-muted-foreground/50 tabular-nums">
                {envFormDesc.length}/160
              </span>
            </label>
            <textarea
              value={envFormDesc}
              onChange={(e) => setEnvFormDesc(e.target.value)}
              maxLength={160}
              rows={2}
              placeholder={t('settings.envDescriptionPlaceholder')}
              className="w-full bg-input-background border border-border rounded-lg px-4 py-2.5 text-body-sm focus:outline-none focus:ring-2 focus:ring-ring/50 focus:border-ring transition-all placeholder:text-muted-foreground resize-none"
            />
          </div>

          <div className="flex items-start justify-between gap-4 p-4 bg-secondary rounded-2xl border border-border">
            <div className="min-w-0">
              <div className="text-body-sm font-medium text-foreground/80 flex items-center gap-1.5">
                <ShieldCheck size={14} className="text-warning" />
                {t('settings.envApproval')}
              </div>
              <p className="text-caption text-muted-foreground mt-1">
                {t('settings.envApprovalHint')}
              </p>
            </div>
            <Switch
              checked={envFormApproval}
              onCheckedChange={setEnvFormApproval}
              className="mt-0.5 shrink-0"
            />
          </div>

          <div className="space-y-2">
            <label className="text-body-sm font-medium text-foreground/80 flex items-center gap-1.5">
              {t('settings.envColor')}
            </label>
            <ColorPicker
              value={envFormColor}
              onChange={setEnvFormColor}
              icon={<Globe size={20} className="text-primary-foreground" />}
              previewName={envFormName}
              previewPlaceholder={t('settings.editEnvName')}
            />
          </div>

          {editingEnv && (apiKeysByEnv[editingEnv.id] || 0) > 0 && (
            <div className="p-4 bg-warning/10 border border-warning/20 rounded-xl">
              <div className="flex items-start gap-3">
                <Key size={18} className="text-warning shrink-0 mt-0.5" />
                <div>
                  <p className="text-body-sm font-semibold text-palette-warning-600 dark:text-palette-warning-700">
                    {t('settings.envApiKeyCount', { count: String(apiKeysByEnv[editingEnv.id] || 0) })}
                  </p>
                  <p className="text-caption text-palette-warning-600 dark:text-palette-warning-700 mt-1">
                    {t('settings.deleteEnvBlocked')}
                  </p>
                </div>
              </div>
            </div>
          )}
        </div>
      </SidePanel>

      {user?.role === 'admin' && (
        <ConfirmDialog
          open={resetOpen}
          onOpenChange={setResetOpen}
          title={t('settings.resetProjectConfirmTitle', { name: project?.name ?? '' })}
          description={t('settings.resetProjectDescription')}
          confirmLabel={t('settings.resetProject')}
          confirmPhrase={project?.name ?? ''}
          onConfirm={() => {
            setResettingProject(true);
            resetProjectMutation.mutate();
          }}
          loading={resettingProject}
        />
      )}

      <PluginSlot slotId="settings.premium" />

      <ConfirmDialog
        open={!!deleteEnvId}
        onOpenChange={(open) => {
          if (!open) setDeleteEnvId(null);
        }}
        title={t('settings.deleteEnvConfirm')}
        description={t('settings.deleteEnvDescription', {
          name: environments.find((e) => e.id === deleteEnvId)?.name ?? '',
        })}
        confirmLabel={t('common.delete')}
        confirmPhrase={environments.find((e) => e.id === deleteEnvId)?.name}
        onConfirm={removeEnv}
        loading={deletingEnv}
      />

    </div>
  );
}
