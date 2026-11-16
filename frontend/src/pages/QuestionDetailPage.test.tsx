import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuestionDetailPage } from './QuestionDetailPage';
import type { QuestionDetail } from '../types';

const { apiMock, state } = vi.hoisted(() => ({
  apiMock: {
    question: vi.fn(), related: vi.fn(), vote: vi.fn(), comment: vi.fn(), answer: vi.fn(),
    accept: vi.fn(), endorse: vi.fn(), bounty: vi.fn(), deleteQuestion: vi.fn(),
  },
  state: { me: null as null | { id: number; displayName: string } },
}));

vi.mock('../api', () => ({ api: apiMock }));
vi.mock('../auth', () => ({
  useAuth: () => ({ me: state.me, loading: false, login: vi.fn(), logout: vi.fn(), refresh: vi.fn() }),
}));

const author = { id: 5, displayName: '易子恒', role: 'STUDENT', verified: false };

const detail: QuestionDetail = {
  id: 42,
  title: '红黑树插入失衡怎么调整',
  body: '正文',
  bodyHtml: '<p>插入后旋转逻辑写错</p>',
  tags: ['数据结构', '红黑树'],
  course: { id: 7, code: 'CS101', name: '数据结构与算法' },
  author,
  score: 3,
  answerCount: 1,
  viewCount: 12,
  myVote: 0,
  createdAt: '2026-11-12T08:00:00Z',
  permissions: { canEdit: true, canDelete: true, canAccept: true, canManage: true },
  comments: [{ id: 1, author, body: '同问', createdAt: '2026-11-12T08:10:00Z' }],
  answers: [
    {
      id: 11, body: '回答正文', bodyHtml: '<p>调整旋转方向即可</p>', author, score: 5, accepted: false,
      myVote: 0, canEdit: false, createdAt: '2026-11-12T09:00:00Z', comments: [],
    },
  ],
};

const renderPage = () =>
  render(
    <MemoryRouter initialEntries={['/questions/42']}>
      <Routes>
        <Route path="/questions/:id" element={<QuestionDetailPage />} />
        <Route path="/login" element={<p>登录页</p>} />
        <Route path="/questions/43" element={<p>另一道题</p>} />
      </Routes>
    </MemoryRouter>,
  );

describe('QuestionDetailPage', () => {
  beforeEach(() => {
    state.me = { id: 9, displayName: '林小问' };
    apiMock.question.mockResolvedValue(detail);
    apiMock.related.mockResolvedValue([{ id: 43, title: 'AVL 树怎么旋转', answerCount: 2, accepted: true }]);
    apiMock.vote.mockResolvedValue({ myVote: 1, scoreDelta: 1 });
    apiMock.comment.mockResolvedValue({ id: 2 });
    apiMock.answer.mockResolvedValue({ id: 12 });
    apiMock.accept.mockResolvedValue(undefined);
    apiMock.endorse.mockResolvedValue(undefined);
    apiMock.bounty.mockResolvedValue({ id: 1 });
    apiMock.deleteQuestion.mockResolvedValue(undefined);
  });

  it('渲染标题、作者、浏览量、课程、标签与后端渲染的 HTML', async () => {
    renderPage();

    expect(await screen.findByRole('heading', { name: '红黑树插入失衡怎么调整' })).toBeInTheDocument();
    expect(screen.getAllByText(/易子恒/).length).toBeGreaterThan(0);
    expect(screen.getByText(/12 次浏览/)).toBeInTheDocument();
    expect(screen.getByText(/课程 数据结构与算法/)).toBeInTheDocument();
    expect(screen.getByText('红黑树')).toBeInTheDocument();
    expect(screen.getByText('插入后旋转逻辑写错')).toBeInTheDocument();
    expect(screen.getByText('调整旋转方向即可')).toBeInTheDocument();
  });

  it('加载时先显示占位文案', () => {
    apiMock.question.mockReturnValue(new Promise(() => undefined));
    renderPage();

    expect(screen.getByText('加载中…')).toBeInTheDocument();
  });

  it('相关问题列表来自 related 接口，为空时显示暂无', async () => {
    renderPage();

    expect(await screen.findByRole('link', { name: 'AVL 树怎么旋转' })).toHaveAttribute('href', '/questions/43');

    apiMock.related.mockResolvedValue([]);
    renderPage();
    await waitFor(() => expect(screen.getAllByText('暂无').length).toBeGreaterThan(0));
  });

  it('按 permissions 决定是否显示编辑/删除/发起悬赏/采纳按钮', async () => {
    renderPage();
    await screen.findByRole('heading', { name: '红黑树插入失衡怎么调整' });

    expect(screen.getByRole('button', { name: '编辑' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '删除' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '发起悬赏' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '采纳此回答' })).toBeInTheDocument();
  });

  it('没有权限时不显示编辑、删除、悬赏与采纳', async () => {
    apiMock.question.mockResolvedValue({
      ...detail,
      permissions: { canEdit: false, canDelete: false, canAccept: false, canManage: false },
    });
    renderPage();
    await screen.findByRole('heading', { name: '红黑树插入失衡怎么调整' });

    expect(screen.queryByRole('button', { name: '编辑' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '删除' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '发起悬赏' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '采纳此回答' })).not.toBeInTheDocument();
  });

  it('给问题投票后重新拉取详情', async () => {
    renderPage();
    await screen.findByRole('heading', { name: '红黑树插入失衡怎么调整' });

    fireEvent.click(screen.getAllByRole('button', { name: '▲' })[0]);

    await waitFor(() => expect(apiMock.vote).toHaveBeenCalledWith('QUESTION', 42, 1));
    await waitFor(() => expect(apiMock.question).toHaveBeenCalledTimes(2));
  });

  it('给回答投票带上回答 id', async () => {
    renderPage();
    await screen.findByRole('heading', { name: '红黑树插入失衡怎么调整' });

    fireEvent.click(screen.getAllByRole('button', { name: '▲' })[1]);

    await waitFor(() => expect(apiMock.vote).toHaveBeenCalledWith('ANSWER', 11, 1));
  });

  it('发表评论后清空输入并刷新详情', async () => {
    renderPage();
    await screen.findByRole('heading', { name: '红黑树插入失衡怎么调整' });

    fireEvent.click(screen.getAllByRole('button', { name: '添加评论' })[0]);
    const input = screen.getByPlaceholderText('评论，可用 @昵称 提醒他人');
    fireEvent.change(input, { target: { value: '我也遇到了' } });
    fireEvent.click(screen.getByRole('button', { name: '发表' }));

    await waitFor(() => expect(apiMock.comment).toHaveBeenCalledWith('QUESTION', 42, '我也遇到了'));
    await waitFor(() => expect(apiMock.question).toHaveBeenCalledTimes(2));
  });

  it('提交回答后清空草稿', async () => {
    renderPage();
    await screen.findByRole('heading', { name: '红黑树插入失衡怎么调整' });

    const box = screen.getByPlaceholderText('支持 Markdown，代码请使用 ``` 包裹');
    fireEvent.change(box, { target: { value: '先做左旋再做右旋' } });
    fireEvent.click(screen.getByRole('button', { name: '提交回答' }));

    await waitFor(() => expect(apiMock.answer).toHaveBeenCalledWith(42, '先做左旋再做右旋'));
    await waitFor(() => expect(box).toHaveValue(''));
  });

  it('删除需要确认，确认后才调用接口', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false);
    renderPage();
    await screen.findByRole('heading', { name: '红黑树插入失衡怎么调整' });

    fireEvent.click(screen.getByRole('button', { name: '删除' }));
    expect(apiMock.deleteQuestion).not.toHaveBeenCalled();

    confirmSpy.mockReturnValue(true);
    fireEvent.click(screen.getByRole('button', { name: '删除' }));
    await waitFor(() => expect(apiMock.deleteQuestion).toHaveBeenCalledWith(42));
    confirmSpy.mockRestore();
  });

  it('未登录时投票按钮禁用、没有写回答框，并提示先登录', async () => {
    state.me = null;
    renderPage();
    await screen.findByRole('heading', { name: '红黑树插入失衡怎么调整' });

    expect(screen.getAllByRole('button', { name: '▲' })[0]).toBeDisabled();
    expect(screen.queryByPlaceholderText('支持 Markdown，代码请使用 ``` 包裹')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: '登录' })).toHaveAttribute('href', '/login');
  });

  it('详情接口失败时显示错误文案', async () => {
    apiMock.question.mockRejectedValue(new Error('问题不存在'));
    renderPage();

    expect(await screen.findByText('问题不存在')).toBeInTheDocument();
  });
});
