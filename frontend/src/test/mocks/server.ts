import { setupServer } from 'msw/node';
import { ws } from 'msw';
import { handlers } from './handlers';

// Existing REST integration tests exercise the polling fallback. Realtime delivery has
// dedicated transport tests and browser WS Mocks, so reject subscriptions explicitly here.
const progress = ws.link('ws://localhost:3000/ws/progress');
export const server = setupServer(
  ...handlers,
  progress.addEventListener('connection', ({ client }) => {
    client.addEventListener('message', (event) => {
      const request = JSON.parse(String(event.data));
      client.send(
        JSON.stringify(
          request.action === 'SUBSCRIBE'
            ? { type: 'SUBSCRIPTION_REJECTED', code: 'UNAVAILABLE' }
            : { type: 'UNSUBSCRIBED' },
        ),
      );
    });
    client.send(JSON.stringify({ type: 'CONNECTED' }));
  }),
);
