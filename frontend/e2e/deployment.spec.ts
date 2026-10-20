import { expect, test } from '@playwright/test';

test('工作台：初次只请求一页五份文档，按更新时间和 ID 倒序显示', async ({ page }) => {
  const documentRequests: string[] = [];
  let recentRequestedAt = 0;
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname;
    if (
      path === '/api/v1/dashboard/documents' ||
      /^\/api\/v1\/knowledge-bases\/\d+\/documents$/.test(path)
    ) {
      documentRequests.push(path);
      if (path === '/api/v1/dashboard/documents') recentRequestedAt = Date.now();
    }
  });
  const started = Date.now();
  let recentApiMs = 0;
  // A cold browser must also download the application bundle and fonts. Start
  // the API budget when its request is sent, separately from page navigation.
  const [response] = await Promise.all([
    page
      .waitForResponse(
        (response) => new URL(response.url()).pathname === '/api/v1/dashboard/documents',
        { timeout: 60_000 },
      )
      .then((response) => {
        recentApiMs = Date.now() - recentRequestedAt;
        return response;
      }),
    page.goto('/', { waitUntil: 'domcontentloaded', timeout: 60_000 }),
  ]);
  expect(response.status()).toBe(200);
  expect(recentRequestedAt).toBeGreaterThan(0);
  expect(recentApiMs).toBeLessThan(15_000);
  const body = await response.json();
  expect(body.code).toBe('SUCCESS');
  expect(body.data.page).toBe(0);
  expect(body.data.size).toBe(5);
  const documents = body.data.content as {
    id: number;
    knowledgeBaseId: number;
    originalName: string;
    updatedAt: string;
  }[];
  expect(documents.length).toBeLessThanOrEqual(5);
  expect(documents).toEqual(
    [...documents].sort(
      (left, right) =>
        Date.parse(right.updatedAt) - Date.parse(left.updatedAt) || right.id - left.id,
    ),
  );
  const card = page.locator('#recent-documents');
  await expect(card.locator('a')).toHaveCount(documents.length);
  for (let index = 0; index < documents.length; index++) {
    const document = documents[index];
    await expect(card.locator('a').nth(index)).toHaveText(document.originalName);
    await expect(card.locator('a').nth(index)).toHaveAttribute(
      'href',
      `/knowledge-bases/${document.knowledgeBaseId}/documents/${document.id}`,
    );
  }
  if (!documents.length) {
    await expect(card.getByText('进入知识库上传第一份文档。')).toBeVisible();
  }
  await expect(card.getByText('正在加载文档…')).toHaveCount(0);
  expect(documentRequests).toEqual(['/api/v1/dashboard/documents']);
  await test.info().attach('recent-documents.json', {
    body: JSON.stringify(
      { elapsedMs: Date.now() - started, recentApiMs, documentRequests, documents },
      null,
      2,
    ),
    contentType: 'application/json',
  });
  await test.info().attach('recent-documents.png', {
    body: await card.screenshot(),
    contentType: 'image/png',
  });
});

test('工作台：两个列表独立滚轮加载下一页，标题固定且长描述不溢出', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const requests = { 'knowledge-bases': [] as number[], documents: [] as number[] };
  page.on('request', (request) => {
    const url = new URL(request.url());
    for (const endpoint of ['knowledge-bases', 'documents'] as const) {
      if (url.pathname === `/api/v1/dashboard/${endpoint}`) {
        requests[endpoint].push(Number(url.searchParams.get('page')));
      }
    }
  });
  await page.goto('/');
  const kbPanel = page.getByRole('region', { name: '最近知识库列表' });
  const docPanel = page.getByRole('region', { name: '最近文档列表' });
  await expect(kbPanel.locator('.ant-list-item')).toHaveCount(5);
  await expect(docPanel.locator('.ant-list-item')).toHaveCount(5);
  expect(requests).toEqual({ 'knowledge-bases': [0], documents: [0] });
  await expect(kbPanel.locator('.ant-list-item-meta-description')).toHaveCount(5);
  const textBounds = await kbPanel
    .locator('.ant-list-item-meta-description')
    .evaluateAll((elements) =>
      elements.map((element) => ({
        width: element.clientWidth,
        contentWidth: element.scrollWidth,
      })),
    );
  for (const bound of textBounds) expect(bound.contentWidth).toBeLessThanOrEqual(bound.width + 1);

  for (const [endpoint, panel, otherPanel] of [
    ['knowledge-bases', kbPanel, docPanel],
    ['documents', docPanel, kbPanel],
  ] as const) {
    await panel.scrollIntoViewIfNeeded();
    const header = page.locator(`#recent-${endpoint} .ant-card-head`);
    const headerBefore = await header.boundingBox();
    const otherBefore = await otherPanel.evaluate((element) => element.scrollTop);
    await panel.hover();
    const next = page.waitForResponse((response) => {
      const url = new URL(response.url());
      return (
        url.pathname === `/api/v1/dashboard/${endpoint}` && url.searchParams.get('page') === '1'
      );
    });
    await page.mouse.wheel(0, 700);
    const response = await next;
    expect(response.status()).toBe(200);
    const result = await response.json();
    await expect(panel.locator('.ant-list-item')).toHaveCount(5 + result.data.content.length);
    expect(requests[endpoint]).toEqual([0, 1]);
    const headerAfter = await header.boundingBox();
    expect(Math.abs(headerAfter!.y - headerBefore!.y)).toBeLessThan(1);
    expect(await otherPanel.evaluate((element) => element.scrollTop)).toBe(otherBefore);
  }
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= document.documentElement.clientWidth,
    ),
  ).toBe(true);
  await test
    .info()
    .attach('dashboard-scroll.png', { body: await page.screenshot(), contentType: 'image/png' });
  await test
    .info()
    .attach('dashboard-pages.json', {
      body: JSON.stringify(requests),
      contentType: 'application/json',
    });
});

test('公网入口：HTTP 转 HTTPS、匿名页面/API/WebSocket 及错误密码被拒绝', async ({
  playwright,
  baseURL,
}) => {
  test.skip(!process.env.PLAYWRIGHT_PUBLIC_CREDENTIALS_FILE, '仅用于有访问密码的公网部署。');
  expect(baseURL).toMatch(/^https:\/\//);
  const configuredCredentials = test.info().project.use.httpCredentials;
  const configuredUsername = (
    Array.isArray(configuredCredentials) ? configuredCredentials[0] : configuredCredentials
  )?.username;
  expect(configuredUsername).toBeTruthy();
  // newContext inherits project options unless explicitly cleared.
  const anonymous = await playwright.request.newContext({ baseURL, httpCredentials: undefined });
  const incorrect = await playwright.request.newContext({
    baseURL,
    httpCredentials: {
      username: configuredUsername!,
      password: 'incorrect-public-acceptance-password',
    },
  });
  try {
    const redirect = await anonymous.get(baseURL!.replace(/^https:/, 'http:') + '/', {
      maxRedirects: 0,
    });
    expect([301, 308]).toContain(redirect.status());
    expect(redirect.headers().location).toBe(baseURL + '/');
    for (const path of ['/', '/api/v1/knowledge-bases?page=0&size=1']) {
      const response = await anonymous.get(path);
      expect(response.status()).toBe(401);
      expect(response.headers()['www-authenticate']).toContain('Basic');
      expect(response.headers()['set-cookie']).toBeUndefined();
    }
    const socket = await anonymous.get('/ws/progress', {
      headers: {
        Connection: 'Upgrade',
        Upgrade: 'websocket',
        'Sec-WebSocket-Key': 'dGhlIHNhbXBsZSBub25jZQ==',
        'Sec-WebSocket-Version': '13',
        Origin: baseURL!,
      },
    });
    expect(socket.status()).toBe(401);
    const wrongPassword = await incorrect.get('/');
    expect(wrongPassword.status()).toBe(401);
    expect(wrongPassword.headers()['set-cookie']).toBeUndefined();
  } finally {
    await anonymous.dispose();
    await incorrect.dispose();
  }
});

test('公网会话：登录一次后仅用 Cookie 访问 API、刷新和重连 WebSocket', async ({
  page,
  context,
  browser,
  playwright,
  baseURL,
}) => {
  test.skip(!process.env.PLAYWRIGHT_PUBLIC_CREDENTIALS_FILE, '仅用于有访问密码的公网部署。');
  const loginResponse = await page.goto('/knowledge-bases');
  const cookieHeader = (await loginResponse!.headerValues('set-cookie')).find((value) =>
    value.startsWith('__Host-aida_session='),
  );
  expect(Boolean(cookieHeader), '登录响应必须包含会话 Cookie').toBe(true);
  expect(/;\s*SameSite=Strict(?:;|$)/i.test(cookieHeader!)).toBe(true);
  const session = (await context.cookies(baseURL!)).find(
    (cookie) => cookie.name === '__Host-aida_session',
  );
  expect(Boolean(session), '正确登录必须签发浏览器会话').toBe(true);
  expect(session!.httpOnly).toBe(true);
  expect(session!.secure).toBe(true);
  // Check the wire policy above: this Windows WebKit build reports "None"
  // through its cookie API even for a Set-Cookie response carrying Strict.
  if (process.platform !== 'win32' || browser.browserType().name() !== 'webkit') {
    expect(session!.sameSite).toBe('Strict');
  }
  expect(session!.path).toBe('/');
  expect(session!.expires).toBe(-1);
  expect(/^[a-f0-9]{64}$/.test(session!.value)).toBe(true);

  // A separate context has no HTTP auth cache or credentials. This catches the
  // missing session that global httpCredentials concealed in earlier tests.
  const cookieOnly = await browser.newContext({
    baseURL,
    httpCredentials: undefined,
    storageState: { cookies: [session!], origins: [] },
  });
  const forged = await playwright.request.newContext({
    baseURL,
    httpCredentials: undefined,
    extraHTTPHeaders: { Cookie: '__Host-aida_session=' + '0'.repeat(64) },
  });
  try {
    const forgedResponse = await forged.get('/api/v1/knowledge-bases?page=0&size=1');
    expect(forgedResponse.status()).toBe(401);
    expect(forgedResponse.headers()['set-cookie']).toBeUndefined();
    const sessionPage = await cookieOnly.newPage();
    const challenges: string[] = [];
    sessionPage.on('response', (response) => {
      if (response.status() === 401 || response.headers()['www-authenticate']) {
        challenges.push(new URL(response.url()).pathname);
      }
    });
    expect((await sessionPage.goto('/knowledge-bases'))?.status()).toBe(200);
    await sessionPage.reload();
    expect(await sessionPage.evaluate(() => document.cookie.includes('__Host-aida_session'))).toBe(
      false,
    );
    const result = await sessionPage.evaluate(async () => {
      const response = await fetch('/api/v1/knowledge-bases?page=0&size=1');
      return { status: response.status, body: await response.json() };
    });
    expect(result.status).toBe(200);
    expect(result.body.code).toBe('SUCCESS');
    for (let attempt = 0; attempt < 3; attempt++) {
      const rejection = await sessionPage.evaluate(
        () =>
          new Promise((resolve, reject) => {
            const url = new URL('/ws/progress', location.href);
            url.protocol = 'wss:';
            const socket = new WebSocket(url);
            const timeout = setTimeout(() => {
              socket.close();
              reject(new Error('仅 Cookie 的实时连接未得到响应'));
            }, 10_000);
            socket.onopen = () =>
              socket.send(
                JSON.stringify({
                  action: 'SUBSCRIBE',
                  resource: 'DOCUMENT',
                  knowledgeBaseId: Number.MAX_SAFE_INTEGER,
                  documentId: Number.MAX_SAFE_INTEGER,
                }),
              );
            socket.onmessage = ({ data }) => {
              const message = JSON.parse(String(data));
              if (message.type !== 'SUBSCRIPTION_REJECTED') return;
              clearTimeout(timeout);
              socket.close();
              resolve(message);
            };
            socket.onerror = () => {
              clearTimeout(timeout);
              socket.close();
              reject(new Error('仅 Cookie 的实时连接鉴权失败'));
            };
          }),
      );
      expect(rejection).toEqual({ type: 'SUBSCRIPTION_REJECTED', code: 'NOT_FOUND' });
    }
    if (
      process.env.PLAYWRIGHT_SESSION_QUERY_KB_ID &&
      process.env.PLAYWRIGHT_SESSION_QUERY_QUESTION
    ) {
      const query = await sessionPage.evaluate(
        async ({ id, question }) => {
          const response = await fetch(`/api/v1/knowledge-bases/${id}/query`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ question, topK: 4, rerank: false, stream: false }),
          });
          return { status: response.status, body: await response.json() };
        },
        {
          id: process.env.PLAYWRIGHT_SESSION_QUERY_KB_ID,
          question: process.env.PLAYWRIGHT_SESSION_QUERY_QUESTION,
        },
      );
      expect(query.status).toBe(200);
      expect(query.body.code).toBe('SUCCESS');
      expect(query.body.data.citations.length).toBeGreaterThan(0);
    }
    expect(challenges).toEqual([]);
  } finally {
    await cookieOnly.close();
    await forged.dispose();
  }
});

test('Nginx：SPA 深链接刷新、静态缓存、缺失资源及 Mock 隔离', async ({ page, request }) => {
  const health = await request.get('/healthz');
  expect(health.status()).toBe(200);
  expect((await health.text()).trim()).toBe('ok');

  const route = '/knowledge-bases/1/chat/1';
  const document = await request.get(route);
  expect(document.status()).toBe(200);
  expect(document.headers()['content-type']).toContain('text/html');
  expect(document.headers()['cache-control']).toContain('no-store');
  const html = await document.text();
  expect(html).toContain('<div id="root"></div>');
  const script = html.match(/src="(\/assets\/[^" ]+\.js)"/);
  expect(script).not.toBeNull();
  const asset = await request.get(script![1]);
  expect(asset.status()).toBe(200);
  expect(asset.headers()['cache-control']).toContain('immutable');
  expect(asset.headers()['x-content-type-options']).toBe('nosniff');

  for (const path of [
    '/assets/does-not-exist.js',
    '/mockServiceWorker.js',
    '/.env',
    '/actuator/health',
  ]) {
    expect((await request.get(path)).status(), path).toBe(404);
  }
  await page.goto(route);
  await page.reload();
  await expect(page.getByRole('link', { name: '返回工作台' })).toBeVisible();
  expect(
    await page.evaluate(() => navigator.serviceWorker.getRegistrations().then((r) => r.length)),
  ).toBe(0);
});

test('Nginx：/api 前缀、查询参数与真实 REST 响应', async ({ request }) => {
  const response = await request.get('/api/v1/knowledge-bases?page=0&size=1');
  expect(response.status()).toBe(200);
  expect(response.headers()['content-type']).toContain('application/json');
  const body = await response.json();
  expect(body.code).toBe('SUCCESS');
  expect(body.data.page).toBe(0);
  expect(body.data.size).toBe(1);
  expect(Array.isArray(body.data.content)).toBe(true);

  const missing = await request.get('/api/v1/fe14-nonexistent-endpoint');
  expect(missing.status()).toBe(404);
  expect(missing.headers()['content-type']).not.toContain('text/html');
});

test('Nginx：真实 WebSocket Upgrade、服务器鉴权与同源检查', async ({ page, request }) => {
  await page.goto('/knowledge-bases');
  // The real backend authorizes before looking up the deliberately absent resource.
  // NOT_FOUND, rather than FORBIDDEN, proves the server secret reached the handshake.
  const rejection = await page.evaluate(
    () =>
      new Promise((resolve, reject) => {
        const url = new URL('/ws/progress', window.location.href);
        url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
        const socket = new WebSocket(url);
        const timeout = window.setTimeout(() => {
          socket.close();
          reject(new Error('真实 WebSocket 没有返回订阅结果'));
        }, 10_000);
        socket.onopen = () =>
          socket.send(
            JSON.stringify({
              action: 'SUBSCRIBE',
              resource: 'DOCUMENT',
              knowledgeBaseId: Number.MAX_SAFE_INTEGER,
              documentId: Number.MAX_SAFE_INTEGER,
            }),
          );
        socket.onmessage = ({ data }) => {
          const message = JSON.parse(String(data));
          if (message.type === 'SUBSCRIPTION_REJECTED') {
            window.clearTimeout(timeout);
            socket.close();
            resolve(message);
          }
        };
        socket.onerror = () => {
          window.clearTimeout(timeout);
          socket.close();
          reject(new Error('真实 WebSocket 握手失败'));
        };
      }),
  );
  expect(rejection).toEqual({ type: 'SUBSCRIPTION_REJECTED', code: 'NOT_FOUND' });

  const foreignOrigin = await request.get('/ws/progress', {
    headers: {
      Connection: 'Upgrade',
      Upgrade: 'websocket',
      'Sec-WebSocket-Key': 'dGhlIHNhbXBsZSBub25jZQ==',
      'Sec-WebSocket-Version': '13',
      Origin: 'https://untrusted.invalid',
    },
  });
  expect(foreignOrigin.status()).toBe(403);
});
