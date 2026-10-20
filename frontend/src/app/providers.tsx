import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import type { ReactNode } from 'react';
import { useState } from 'react';
import { appTheme } from '../styles/theme';

export function AppProviders({ children }: { children: ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            retry: (failureCount, error) => {
              const status =
                typeof error === 'object' && error && 'httpStatus' in error
                  ? (error as { httpStatus?: number }).httpStatus
                  : undefined;
              return status !== undefined && status < 500 ? false : failureCount < 1;
            },
            refetchOnWindowFocus: false,
          },
          mutations: {
            retry: false,
          },
        },
      }),
  );

  return (
    <ConfigProvider locale={zhCN} theme={appTheme}>
      <AntdApp>
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      </AntdApp>
    </ConfigProvider>
  );
}
