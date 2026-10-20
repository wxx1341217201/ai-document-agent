import { describe, expect, it } from 'vitest';
import { HttpResponse, http } from 'msw';
import { systemApi } from './system.api';
import { server } from '../test/mocks/server';

describe('safe public system health (MSW)', () => {
  it('retains only public component names and simple statuses', async () => {
    server.use(
      http.get('*/api/v1/system/health', () =>
        HttpResponse.json({
          code: 'SUCCESS',
          message: 'OK',
          data: {
            status: 'UP',
            host: 'private-host',
            password: 'private-password',
            components: {
              database: 'UP',
              redis: { status: 'UP', details: { url: 'redis://private' } },
              rabbitmq: 'DOWN',
              secretComponent: 'UP',
            },
          },
        }),
      ),
    );
    expect(await systemApi.health()).toEqual({
      status: 'UP',
      components: { database: 'UP', redis: 'UNKNOWN', rabbitmq: 'DOWN' },
    });
  });
  it('rejects raw Actuator/invalid envelopes and surfaces a missing public API', async () => {
    server.use(
      http.get('*/api/v1/system/health', () =>
        HttpResponse.json({ status: 'UP', components: { database: { details: 'private' } } }),
      ),
    );
    await expect(systemApi.health()).rejects.toMatchObject({ code: 'INVALID_API_RESPONSE' });
    server.use(http.get('*/api/v1/system/health', () => new HttpResponse(null, { status: 404 })));
    await expect(systemApi.health()).rejects.toMatchObject({ httpStatus: 404 });
  });
});
