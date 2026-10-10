import React, { useRef, useState } from 'react';
import { toast } from 'sonner';
import { Camera, LogOut, ChevronDown, Loader2, Globe } from '@/shared/icons';
import { useAuth } from '@/app/auth/useAuth';
import { api } from '@/api';
import { useAuthedImage } from '@/app/hooks/queries';
import { useLocale, useT } from '@/i18n';
import { getErrorMessage } from '@/shared';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
} from '@/app/components/ui/dropdown-menu';
import { Avatar, AvatarImage, AvatarFallback } from '@/app/components/ui/avatar';
import { Badge } from '@/shared/components/Badge';

export function UserProfileMenu() {
  const { user, logout, updateUser } = useAuth();
  const { locale, setLocale } = useLocale();
  const t = useT();
  const [avatarVersion, setAvatarVersion] = useState(0);
  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const initials = user?.name
    ? user.name
        .split(' ')
        .map((n: string) => n[0])
        .join('')
        .toUpperCase()
    : (user?.email?.charAt(0).toUpperCase() ?? '?');

  const avatarPath = user ? `${api.users.getAvatarUrl(user.id)}?v=${avatarVersion}` : null;
  const hasAvatar = !!(user && user.avatar);
  const avatarUrl = useAuthedImage(hasAvatar ? avatarPath : null);

  const handleUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || !user) return;
    const maxBytes = 2 * 1024 * 1024;
    if (file.size > maxBytes) {
      toast.error(t('errors.upload.fileTooLarge', { max: '2' }));
      e.target.value = '';
      return;
    }
    setUploading(true);
    api.users
      .uploadAvatar(user.id, file)
      .then((updatedUser) => {
        updateUser(updatedUser);
        setAvatarVersion((v) => v + 1);
      })
      .catch((err) => {
        toast.error(getErrorMessage(err));
      })
      .finally(() => setUploading(false));
    e.target.value = '';
  };

  const roleVariantMap: Record<string, 'warning' | 'info' | 'default'> = {
    admin: 'warning',
    developer: 'info',
  };

  const getRoleVariant = (role: string): 'warning' | 'info' | 'default' =>
    roleVariantMap[role] ?? 'default';

  const getRoleLabel = (role: string) => {
    switch (role) {
      case 'admin':
        return t('userMenu.roleAdmin');
      case 'developer':
        return t('userMenu.roleDeveloper');
      case 'viewer':
        return t('userMenu.roleViewer');
      default:
        return role;
    }
  };

  return (
    <>
      <input
        ref={fileInputRef}
        type="file"
        accept="image/png,image/jpeg,image/gif,image/webp,image/svg+xml"
        className="hidden"
        onChange={handleUpload}
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
        <button
          className="flex items-center gap-2 text-body-sm text-muted-foreground hover:text-foreground transition-colors p-1 rounded-full"
          aria-label={t('common.openUserMenu')}
        >
          <Avatar className="w-8 h-8 ring-2 ring-background dark:ring-border shadow-sm">
            <AvatarImage src={avatarUrl ?? undefined} alt={user?.name ?? ''} />
            <AvatarFallback className="bg-brand text-caption font-bold text-primary-foreground">
              {initials}
            </AvatarFallback>
          </Avatar>
          <span className="hidden sm:block max-w-[160px] truncate">
            {user?.name ?? user?.email ?? '—'}
          </span>
          <ChevronDown size={14} className="text-muted-foreground/70" />
        </button>
      </DropdownMenuTrigger>

      <DropdownMenuContent align="end" className="w-64 p-2">
        <div className="flex items-center gap-3 px-2 py-2">
          <Avatar className="w-12 h-12 ring-2 ring-border dark:ring-border shadow-sm">
            <AvatarImage src={avatarUrl ?? undefined} alt={user?.name ?? ''} />
            <AvatarFallback className="bg-brand text-body-sm font-bold text-primary-foreground">
              {initials}
            </AvatarFallback>
          </Avatar>
          <div className="min-w-0 flex-1">
            <div className="text-body-sm font-semibold text-foreground truncate">
              {user?.name ?? user?.email ?? '—'}
            </div>
            <div className="text-caption text-muted-foreground truncate">{user?.email}</div>
            <Badge variant={getRoleVariant(user?.role ?? 'viewer')} size="sm">
              {getRoleLabel(user?.role ?? 'viewer')}
            </Badge>
          </div>
        </div>

        <DropdownMenuSeparator />

        <DropdownMenuItem
          onSelect={(e) => {
            e.preventDefault();
            setTimeout(() => fileInputRef.current?.click(), 0);
          }}
          className="cursor-pointer"
          disabled={uploading}
        >
          {uploading ? <Loader2 size={16} className="animate-spin" /> : <Camera size={16} />}
          {uploading
            ? t('common.loading')
            : hasAvatar
              ? t('userMenu.changePhoto')
              : t('userMenu.uploadPhoto')}
        </DropdownMenuItem>

        <DropdownMenuSeparator />

        <DropdownMenuRadioGroup
          value={locale}
          onValueChange={(value) => setLocale(value as 'ru' | 'en')}
        >
          <DropdownMenuRadioItem value="ru" className="cursor-pointer">
            <Globe size={16} />
            Русский
          </DropdownMenuRadioItem>
          <DropdownMenuRadioItem value="en" className="cursor-pointer">
            <Globe size={16} />
            English
          </DropdownMenuRadioItem>
        </DropdownMenuRadioGroup>

        <DropdownMenuSeparator />

        <DropdownMenuItem
          onClick={logout}
          className="cursor-pointer text-destructive focus:text-destructive"
        >
          <LogOut size={16} />
          {t('auth.logout')}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
    </>
  );
}
