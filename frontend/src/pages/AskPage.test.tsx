import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AskPage } from './AskPage';

const { askMock } = vi.hoisted(() => ({ askMock: vi.fn() }));

vi.mock('../api', () => ({ api: { ask: askMock } }));
vi.mock('../auth', () => ({
  useAuth: () => ({
    me: {
      id: 9, username: '20240001', displayName: '林小问', email: 'lin@example.com', role: 'STUDENT',
      verified: false, college: '计算机工程学院', className: '软件工程2024级',
      courses: [
        { id: 7, code: 'CS101', name: '数据结构与算法', term: '2026秋', teacherName: '张老师' },
        { id: 8, code: 'CS102', name: '操作系统', term: '2026秋', teacherName: '李老师' },
      ],
    },
    loading: false, login: vi.fn(), logout: vi.fn(), refresh: vi.fn(),
  }),
}));

const renderPage = () =>
  render(
    <MemoryRouter initialEntries={['/ask']}>
      <Routes>
        <Route path="/ask" element={<AskPage />} />
        <Route path="/questions/:id" element={<p>问题详情页</p>} />
      </Routes>
    </MemoryRouter>,
  );

const fill = (label: RegExp | string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });

describe('AskPage', () => {
  beforeEach(() => {
    askMock.mockResolvedValue({ id: 99 });
  });

  it('标签按中英文逗号与空格切分并去掉空项', async () => {
    const { container } = renderPage();
    fill(/标题/, '红黑树插入失衡怎么调整');
    fill(/正文/, '我在实现红黑树时，插入后旋转逻辑写错，导致树高不平衡。');
    fill(/标签/, '数据结构，红黑树  算法,');

    fireEvent.click(screen.getByRole('button', { name: '发布问题' }));

    await waitFor(() => expect(askMock).toHaveBeenCalledTimes(1));
    expect(askMock.mock.calls[0][0].tags).toEqual(['数据结构', '红黑树', '算法']);
    expect(container).toBeTruthy();
  });

  it('未选择课程时 courseId 为 undefined', async () => {
    renderPage();
    fill(/标题/, '第一个问题标题');
    fill(/正文/, '正文内容足够长，说明我尝试过什么、卡在哪里。');

    fireEvent.click(screen.getByRole('button', { name: '发布问题' }));

    await waitFor(() => expect(askMock).toHaveBeenCalledTimes(1));
    expect(askMock.mock.calls[0][0].courseId).toBeUndefined();
  });

  it('选择课程时把课程 id 转成数字', async () => {
    renderPage();
    fill(/标题/, '关联到课程的问题');
    fill(/正文/, '正文内容足够长，说明我尝试过什么、卡在哪里。');
    fireEvent.change(screen.getByLabelText(/所属课程/), { target: { value: '7' } });

    fireEvent.click(screen.getByRole('button', { name: '发布问题' }));

    await waitFor(() => expect(askMock).toHaveBeenCalledTimes(1));
    expect(askMock.mock.calls[0][0].courseId).toBe(7);
  });

  it('课程下拉列出「我加入的课程」', () => {
    renderPage();

    expect(screen.getByRole('option', { name: 'CS101 数据结构与算法' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'CS102 操作系统' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: '不关联课程' })).toBeInTheDocument();
  });

  it('发布成功后跳到新问题详情页', async () => {
    renderPage();
    fill(/标题/, '发布成功要跳转');
    fill(/正文/, '正文内容足够长，说明我尝试过什么、卡在哪里。');

    fireEvent.click(screen.getByRole('button', { name: '发布问题' }));

    expect(await screen.findByText('问题详情页')).toBeInTheDocument();
  });

  it('发布失败时显示后端业务提示且不跳转', async () => {
    askMock.mockRejectedValue(new Error('标题长度应为 10–150 字'));
    renderPage();
    fill(/标题/, '太短');
    fill(/正文/, '正文内容足够长，说明我尝试过什么、卡在哪里。');

    fireEvent.click(screen.getByRole('button', { name: '发布问题' }));

    expect(await screen.findByText('标题长度应为 10–150 字')).toBeInTheDocument();
    expect(screen.queryByText('问题详情页')).not.toBeInTheDocument();
  });
});
