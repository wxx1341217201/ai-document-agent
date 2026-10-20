import { PageHeader } from '../../components/layout/PageHeader';
import { SystemHealthCard } from './SystemHealthCard';

export function SystemPage() {
  return (
    <>
      <PageHeader
        title="系统状态"
        description="显示应用和基础设施的公开健康状态。"
      />
      <SystemHealthCard />
    </>
  );
}
