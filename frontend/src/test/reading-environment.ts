import { builtinEnvironments } from 'vitest/environments';
import type { Environment } from 'vitest/environments';

// React Router uses Node's Request in this suite. Keep its matching signal classes
// while JSDOM provides DOM elements; mixing the two realms rejects navigation.
export default {
  ...builtinEnvironments.jsdom,
  name: 'reading-jsdom',
  async setup(global, options) {
    const controller = global.AbortController;
    const signal = global.AbortSignal;
    const environment = await builtinEnvironments.jsdom.setup(global, {
      ...options,
      jsdom: {
        ...options.jsdom,
        beforeParse(window: Window) {
          Object.defineProperty(window, 'AbortController', {
            value: controller,
            configurable: true,
          });
          Object.defineProperty(window, 'AbortSignal', { value: signal, configurable: true });
        },
      },
    });
    global.AbortController = controller;
    global.AbortSignal = signal;
    return environment;
  },
} satisfies Environment;
