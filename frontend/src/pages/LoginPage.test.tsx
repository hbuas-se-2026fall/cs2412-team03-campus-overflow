import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { LoginPage } from './LoginPage';

const { registerMock, loginMock } = vi.hoisted(() => ({ registerMock: vi.fn(), loginMock: vi.fn() }));

vi.mock('../api', () => ({ api: { register: registerMock } }));
vi.mock('../auth', () => ({
  useAuth: () => ({ me: null, loading: false, login: loginMock, logout: vi.fn(), refresh: vi.fn() }),
}));

const renderPage = () =>
  render(
    <MemoryRouter initialEntries={['/login']}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/" element={<p>首页已登录</p>} />
      </Routes>
    </MemoryRouter>,
  );

const submitButton = (container: HTMLElement) => container.querySelector('button.primary') as HTMLButtonElement;

const fill = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });

describe('LoginPage', () => {
  beforeEach(() => {
    loginMock.mockResolvedValue(undefined);
    registerMock.mockResolvedValue({ id: 1 });
  });

  it('默认是登录模式：只有账号密码两个输入框，不显示注册字段', () => {
    renderPage();

    expect(screen.getByLabelText('学号 / 工号')).toBeInTheDocument();
    expect(screen.getByLabelText('密码')).toBeInTheDocument();
    expect(screen.queryByLabelText('昵称')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('校园邮箱')).not.toBeInTheDocument();
  });

  it('登录成功后调用 login 并跳到首页，不碰注册接口', async () => {
    const { container } = renderPage();
    fill('学号 / 工号', '20240001');
    fill('密码', 'abc12345');

    fireEvent.click(submitButton(container));

    await waitFor(() => expect(screen.getByText('首页已登录')).toBeInTheDocument());
    expect(loginMock).toHaveBeenCalledWith('20240001', 'abc12345');
    expect(registerMock).not.toHaveBeenCalled();
  });

  it('切到注册模式后出现昵称/邮箱/学院/班级四个字段', () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: '注册' }));

    expect(screen.getByLabelText('昵称')).toBeInTheDocument();
    expect(screen.getByLabelText('校园邮箱')).toBeInTheDocument();
    expect(screen.getByLabelText('学院')).toBeInTheDocument();
    expect(screen.getByLabelText('班级')).toBeInTheDocument();
  });

  it('注册模式提交时先注册再登录，然后跳首页', async () => {
    const { container } = renderPage();
    fireEvent.click(screen.getByRole('button', { name: '注册' }));
    fill('学号 / 工号', '20240002');
    fill('密码', 'abc12345');
    fill('昵称', '林小问');
    fill('校园邮箱', 'lin@example.com');
    fill('学院', '计算机工程学院');
    fill('班级', '软件工程2024级');

    fireEvent.click(submitButton(container));

    await waitFor(() => expect(screen.getByText('首页已登录')).toBeInTheDocument());
    expect(registerMock).toHaveBeenCalledWith({
      username: '20240002',
      password: 'abc12345',
      displayName: '林小问',
      email: 'lin@example.com',
      college: '计算机工程学院',
      className: '软件工程2024级',
    });
    expect(loginMock).toHaveBeenCalledWith('20240002', 'abc12345');
  });

  it('登录失败时显示后端给的业务提示，且不跳转', async () => {
    loginMock.mockRejectedValue(new Error('学号或密码错误'));
    const { container } = renderPage();
    fill('学号 / 工号', '20240001');
    fill('密码', 'wrong');

    fireEvent.click(submitButton(container));

    expect(await screen.findByText('学号或密码错误')).toBeInTheDocument();
    expect(screen.queryByText('首页已登录')).not.toBeInTheDocument();
  });

  it('注册失败时显示错误且不再尝试登录', async () => {
    registerMock.mockRejectedValue(new Error('该学号/工号已注册'));
    const { container } = renderPage();
    fireEvent.click(screen.getByRole('button', { name: '注册' }));
    fill('学号 / 工号', '20240003');
    fill('密码', 'abc12345');

    fireEvent.click(submitButton(container));

    expect(await screen.findByText('该学号/工号已注册')).toBeInTheDocument();
    expect(loginMock).not.toHaveBeenCalled();
  });
});
