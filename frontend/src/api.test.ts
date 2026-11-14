import { describe, expect, it, vi } from 'vitest';
import { api, ApiError } from './api';

const json = (body: unknown, status = 200) =>
  new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });

/** 装一个 fetch 桩，按顺序返回给定响应；返回桩本身便于断言调用参数。 */
function stubFetch(...responses: Response[]) {
  const fn = vi.fn<typeof fetch>();
  for (const r of responses) fn.mockResolvedValueOnce(r);
  vi.stubGlobal('fetch', fn);
  return fn;
}

const lastCall = (fn: ReturnType<typeof stubFetch>) => {
  const [url, init] = fn.mock.calls[fn.mock.calls.length - 1];
  return { url: String(url), init: init as RequestInit };
};

describe('api 请求层', () => {
  it('GET 会拼上 /api 前缀并带上会话 Cookie', async () => {
    const fn = stubFetch(json({ items: [], total: 0, page: 1, size: 20 }));
    const page = await api.questions();

    const { url, init } = lastCall(fn);
    expect(url).toBe('/api/v1/questions?page=1');
    expect(init.method).toBe('GET');
    expect(init.credentials).toBe('include');
    expect(page.total).toBe(0);
  });

  it('只有传了 courseId 才追加过滤参数', async () => {
    const fn = stubFetch(json({ items: [], total: 0, page: 2, size: 20 }), json({ items: [], total: 0, page: 1, size: 20 }));
    await api.questions(2);
    expect(lastCall(fn).url).toBe('/api/v1/questions?page=2');
    await api.questions(1, 7);
    expect(lastCall(fn).url).toBe('/api/v1/questions?page=1&courseId=7');
  });

  it('204 解析为 undefined，不尝试 JSON.parse', async () => {
    const fn = stubFetch(json({ token: '' }), new Response(null, { status: 204 }));
    await expect(api.logout()).resolves.toBeUndefined();
    expect(lastCall(fn).init.method).toBe('POST');
  });

  it('Problem Details 映射成带业务码的 ApiError', async () => {
    stubFetch(json({ token: '' }), json({ code: 'QA-1001', detail: '问题标题不能为空' }, 422));
    const err = await api.ask({ title: '', body: 'x', tags: [] }).catch((e: unknown) => e);

    expect(err).toBeInstanceOf(ApiError);
    expect(err).toBeInstanceOf(Error);
    const e = err as ApiError;
    expect(e.status).toBe(422);
    expect(e.code).toBe('QA-1001');
    expect(e.message).toBe('问题标题不能为空');
  });

  it('错误响应没有 body 时给出兜底码与文案', async () => {
    stubFetch(new Response(null, { status: 500 }));
    const err = (await api.me().catch((e: unknown) => e)) as ApiError;

    expect(err.code).toBe('SYS-000');
    expect(err.message).toBe('请求失败');
    expect(err.status).toBe(500);
  });

  it('写请求带已有 XSRF-TOKEN Cookie 时不再预取 token', async () => {
    document.cookie = 'XSRF-TOKEN=abc%3D123';
    const fn = stubFetch(json({ id: 1 }));
    await api.register({ username: 's', password: 'p' });

    const { url, init } = lastCall(fn);
    expect(url).toBe('/api/v1/auth/register');
    expect(fn).toHaveBeenCalledTimes(1);
    expect((init.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('abc=123');
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
    expect(init.body).toBe(JSON.stringify({ username: 's', password: 'p' }));
  });

  it('没有 Cookie 时先 GET /auth/csrf 取 token，再发写请求', async () => {
    const fn = vi.fn<typeof fetch>();
    fn.mockImplementationOnce(async (input: RequestInfo | URL) => {
      expect(String(input)).toBe('/api/v1/auth/csrf');
      document.cookie = 'XSRF-TOKEN=fresh-token';
      return json({ token: 'fresh-token' });
    });
    fn.mockResolvedValueOnce(json({ id: 1 }));
    vi.stubGlobal('fetch', fn);

    await api.vote('QUESTION', 9, 1);

    expect(fn).toHaveBeenCalledTimes(2);
    const { url, init } = lastCall(fn);
    expect(url).toBe('/api/v1/votes');
    expect((init.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('fresh-token');
  });

  it('CSRF 预取也拿不到 token 时照常发请求，只是不带该头', async () => {
    const fn = stubFetch(json({ token: '' }), json({ id: 1 }));
    await api.answer(3, '试试');

    const { init } = lastCall(fn);
    expect(init.headers).not.toHaveProperty('X-XSRF-TOKEN');
  });

  it('GET 请求不会被塞 CSRF 头', async () => {
    document.cookie = 'XSRF-TOKEN=abc';
    const fn = stubFetch(json([]));
    await api.popularTags();
    expect(lastCall(fn).init.headers).toEqual({});
  });

  it('search 按需拼接 tag / sort / unanswered，并正确编码中文与空格', async () => {
    const fn = stubFetch(
      json({ items: [], total: 0, page: 1, size: 20 }),
      json({ items: [], total: 0, page: 3, size: 20 }),
    );
    await api.search('中文 空格');
    expect(lastCall(fn).url).toBe('/api/v1/search/questions?q=%E4%B8%AD%E6%96%87+%E7%A9%BA%E6%A0%BC&page=1');

    await api.search('java', { tag: 'spring', sort: 'votes', unanswered: true, page: 3 });
    const { url } = lastCall(fn);
    expect(url).toContain('q=java');
    expect(url).toContain('page=3');
    expect(url).toContain('tag=spring');
    expect(url).toContain('sort=votes');
    expect(url).toContain('unanswered=true');
  });
});
