import { ApiClientError, apiRequest } from './client';
import type { HealthStatus, SystemHealth } from './contracts';
import { isRecord } from './progress.contracts';

const publicComponents = ['database', 'redis', 'rabbitmq', 'qdrant', 'elasticsearch'] as const;
const status = (value: unknown): HealthStatus =>
  value === 'UP' || value === 'DOWN' ? value : 'UNKNOWN';

export const systemApi = {
  async health(signal?: AbortSignal): Promise<SystemHealth> {
    const data = await apiRequest<unknown>({ method: 'GET', url: '/system/health', signal });
    if (!isRecord(data) || typeof data.status !== 'string' || !isRecord(data.components)) {
      throw new ApiClientError({
        code: 'INVALID_API_RESPONSE',
        message: '服务端未返回安全的系统健康状态。',
      });
    }
    const components: SystemHealth['components'] = {};
    // Discard hostnames, credentials, connection strings and Actuator details.
    for (const name of publicComponents) {
      if (Object.hasOwn(data.components, name)) components[name] = status(data.components[name]);
    }
    return { status: status(data.status), components };
  },
};
