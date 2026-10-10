import { createBrowserRouter, Navigate, Outlet } from 'react-router';
import { lazy } from 'react';
import { Auth } from '@/app/components/Auth';
import { ForgotPassword } from '@/app/components/ForgotPassword';
import { ResetPassword } from '@/app/components/ResetPassword';
import { AcceptInvite } from '@/app/components/AcceptInvite';
import { DashboardLayout } from '@/app/components/DashboardLayout';
import { ProtectedRoute } from '@/app/components/ProtectedRoute';
import { AuthProvider } from '@/app/auth/AuthContext';
import { AppSidebarProvider } from '@/app/components/AppSidebar';
import { LazyPage } from '@/shared/components/LazyPage';
import { PremiumPageSlot } from '@/app/components/PremiumPageSlot';
import { PluginSlot } from '@/app/components/PluginSlot';

const Flags = lazy(() => import('@/app/components/Flags').then((m) => ({ default: m.Flags })));
const Overview = lazy(() =>
  import('@/app/components/Overview').then((m) => ({ default: m.Overview })),
);
const Segments = lazy(() =>
  import('@/app/components/Segments').then((m) => ({ default: m.Segments })),
);
const Strategies = lazy(() =>
  import('@/app/components/Strategies').then((m) => ({ default: m.Strategies })),
);
const ApiKeys = lazy(() =>
  import('@/app/components/ApiKeys').then((m) => ({ default: m.ApiKeys })),
);
const ClientInstances = lazy(() =>
  import('@/app/components/ClientInstances').then((m) => ({ default: m.ClientInstances })),
);
const Constraints = lazy(() =>
  import('@/app/components/Constraints').then((m) => ({ default: m.Constraints })),
);
const Tags = lazy(() => import('@/app/components/Tags').then((m) => ({ default: m.Tags })));
const Users = lazy(() => import('@/app/components/Users').then((m) => ({ default: m.Users })));
const AuditLog = lazy(() =>
  import('@/app/components/AuditLog').then((m) => ({ default: m.AuditLog })),
);
const Integrations = lazy(() =>
  import('@/app/components/Integrations').then((m) => ({ default: m.Integrations })),
);
const Settings = lazy(() =>
  import('@/app/components/Settings').then((m) => ({ default: m.Settings })),
);

function RootLayout() {
  return (
    <AuthProvider>
      <Outlet />
    </AuthProvider>
  );
}

export const router = createBrowserRouter([
  {
    Component: RootLayout,
    children: [
      {
        path: '/login',
        Component: Auth,
      },
      {
        path: '/forgot-password',
        Component: ForgotPassword,
      },
      {
        path: '/reset-password',
        Component: ResetPassword,
      },
      {
        path: '/accept-invite',
        Component: AcceptInvite,
      },
      {
        path: '/signup',
        element: <PluginSlot slotId="page.signup" />,
      },
      {
        path: '/confirm-email',
        element: <PluginSlot slotId="page.confirmEmail" />,
      },
      {
        path: '/oauth/callback',
        element: <PluginSlot slotId="page.oauthCallback" />,
      },
      {
        element: <ProtectedRoute />,
        children: [
          {
            path: '/',
            element: <AppSidebarProvider><DashboardLayout /></AppSidebarProvider>,
            children: [
              { index: true, element: <LazyPage Component={Overview} /> },
              { path: 'flags', element: <LazyPage Component={Flags} /> },
              { path: 'segments', element: <LazyPage Component={Segments} /> },
              { path: 'contexts', element: <LazyPage Component={Constraints} /> },
              { path: 'strategies', element: <LazyPage Component={Strategies} /> },
              { path: 'tags', element: <LazyPage Component={Tags} /> },
              { path: 'users', element: <LazyPage Component={Users} /> },
              { path: 'audit', element: <LazyPage Component={AuditLog} /> },
              { path: 'integrations', element: <LazyPage Component={Integrations} /> },
              { path: 'apikeys', element: <LazyPage Component={ApiKeys} /> },
              { path: 'applications', element: <LazyPage Component={ClientInstances} /> },
              { path: 'settings', element: <LazyPage Component={Settings} /> },
              { path: 'premium/*', element: <PremiumPageSlot /> },
            ],
          },
        ],
      },
      {
        path: '*',
        element: <Navigate to="/" replace />,
      },
    ],
  },
]);
