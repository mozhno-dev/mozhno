import React from 'react';

export const PLUGIN_SLOTS = [
  'sidebar.admin',
  'settings.premium',
  'page.premium',
  'auth.social',
  'auth.signup',
  'page.signup',
  'page.confirmEmail',
  'page.oauthCallback',
] as const;

export type PluginSlotId = (typeof PLUGIN_SLOTS)[number];

export interface PremiumPlugin {
  id: number;
  slotId: PluginSlotId;
  Component: React.ComponentType<Record<string, unknown>>;
  requiredPlan?: string;
  priority: number;
  onInit?: () => void | Promise<void>;
  onDestroy?: () => void;
}

class PluginRegistry {
  private plugins = new Map<PluginSlotId, PremiumPlugin[]>();
  private initialized = new Set<number>();
  private nextId = 0;

  register(
    slotId: PluginSlotId,
    Component: React.ComponentType<Record<string, unknown>>,
    options?: {
      requiredPlan?: string;
      priority?: number;
      onInit?: () => void | Promise<void>;
      onDestroy?: () => void;
    },
  ): () => void {
    const existing = this.plugins.get(slotId) || [];

    if (existing.some((p) => p.Component === Component)) {
      return () => this.unregister(slotId, Component);
    }

    const id = ++this.nextId;
    const plugin: PremiumPlugin = {
      id,
      slotId,
      Component,
      requiredPlan: options?.requiredPlan,
      priority: options?.priority ?? 50,
      onInit: options?.onInit,
      onDestroy: options?.onDestroy,
    };

    existing.push(plugin);
    existing.sort((a, b) => a.priority - b.priority);
    this.plugins.set(slotId, existing);

    if (plugin.onInit && !this.initialized.has(id)) {
      this.initialized.add(id);
      try {
        const result = plugin.onInit();
        if (result instanceof Promise) {
          result.catch((err: unknown) => {
            if (import.meta.env.DEV) {
              console.error(`[PluginRegistry] onInit failed for slot "${slotId}":`, err);
            }
          });
        }
      } catch (err) {
        if (import.meta.env.DEV) {
          console.error(`[PluginRegistry] onInit failed for slot "${slotId}":`, err);
        }
      }
    }

    return () => this.unregister(slotId, Component);
  }

  unregister(slotId: PluginSlotId, Component: React.ComponentType<Record<string, unknown>>) {
    const existing = this.plugins.get(slotId) || [];
    const plugin = existing.find((p) => p.Component === Component);

    if (plugin) {
      if (plugin.onDestroy) {
        try {
          plugin.onDestroy();
        } catch (err) {
          if (import.meta.env.DEV) {
            console.error(`[PluginRegistry] onDestroy failed for slot "${slotId}":`, err);
          }
        }
      }
      this.initialized.delete(plugin.id);
    }

    this.plugins.set(
      slotId,
      existing.filter((p) => p.Component !== Component),
    );
  }

  getForSlot(slotId: PluginSlotId): PremiumPlugin[] {
    return this.plugins.get(slotId) || [];
  }

  get registeredSlots(): PluginSlotId[] {
    return Array.from(this.plugins.keys());
  }
}

export const pluginRegistry = new PluginRegistry();
