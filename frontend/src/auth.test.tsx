import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider, useAuth } from './auth';
import type { Me } from './types';

const { meMock, csrfMock, loginMock, logoutMock } = vi.hoisted(() => ({
  meMock: vi.fn(),
  csrfMock: vi.fn(),
  loginMock: vi.fn(),
  logoutMock: vi.fn(),
}));

vi.mock('./api', () => ({ api: { me: meMock, csrf: csrfMock, login: loginMock, logout: logoutMock } }));

const me: Me = {
  id: 9, username: 'liuhaoran', displayName: '刘浩然', email: 'a@b.c', role: 'STUDENT',
  verified: true, courses: [],
};

/** 把上下文状态摊到 DOM 上，便于用断言代替快照。 */
function Probe() {
  const { me: current, loading, login, logout } = useAuth();
  return (
    <div>
      <span data-testid="state">{loading ? 'loading' : current ? `in:${current.displayName}` : 'anon'}</span>
      <button onClick={() => void login('liuhaoran', 'pw')}>登录</button>
      <button onClick={() => void logout()}>登出</button>
    </div>
  );
}

const renderProbe = () =>
  render(
    <AuthProvider>
      <Probe />
    </AuthProvider>,
  );

describe('AuthProvider / useAuth', () => {
  beforeEach(() => {
    csrfMock.mockResolvedValue({ token: 't' });
    meMock.mockResolvedValue(me);
    loginMock.mockResolvedValue(me);
    logoutMock.mockResolvedValue(undefined);
  });

  it('挂载时先取 CSRF 再取 me，期间是 loading', async () => {
    renderProbe();

    expect(screen.getByTestId('state')).toHaveTextContent('loading');
    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('in:刘浩然'));
    expect(csrfMock).toHaveBeenCalledTimes(1);
    expect(meMock).toHaveBeenCalledTimes(1);
  });

  it('me 返回 401 时降级为未登录，而不是一直 loading', async () => {
    meMock.mockRejectedValue(new Error('401'));
    renderProbe();

    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('anon'));
  });

  it('CSRF 预取失败不影响加载流程', async () => {
    csrfMock.mockRejectedValue(new Error('csrf failed'));
    renderProbe();

    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('in:刘浩然'));
  });

  it('login 成功后上下文切到已登录', async () => {
    meMock.mockRejectedValue(new Error('401'));
    renderProbe();
    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('anon'));

    fireEvent.click(screen.getByRole('button', { name: '登录' }));

    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('in:刘浩然'));
    expect(loginMock).toHaveBeenCalledWith('liuhaoran', 'pw');
  });

  it('logout 成功后上下文回到未登录', async () => {
    renderProbe();
    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('in:刘浩然'));

    fireEvent.click(screen.getByRole('button', { name: '登出' }));

    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('anon'));
    expect(logoutMock).toHaveBeenCalledTimes(1);
  });

  it('在 AuthProvider 之外调用 useAuth 直接报错，避免静默拿到空上下文', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    expect(() => render(<Probe />)).toThrow('useAuth 必须在 AuthProvider 内使用');
    spy.mockRestore();
  });
});
