import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationsPage } from './NotificationsPage';
import type { Notification } from '../types';

const { notificationsMock, readAllMock } = vi.hoisted(() => ({
  notificationsMock: vi.fn(),
  readAllMock: vi.fn(),
}));

vi.mock('../api', () => ({ api: { notifications: notificationsMock, readAll: readAllMock } }));

const page = (items: Notification[]) => ({ items, total: items.length, page: 1, size: 20 });

const unread: Notification = {
  id: 1, type: 'ANSWER', title: '你的问题有新回答', link: '/questions/9', read: false,
  createdAt: '2026-11-12T08:00:00Z',
};
const read: Notification = {
  id: 2, type: 'BADGE', title: '获得「首次采纳」徽章', link: '/users/9/reputation', read: true,
  createdAt: '2026-11-11T08:00:00Z',
};

const renderPage = () =>
  render(
    <MemoryRouter>
      <NotificationsPage />
    </MemoryRouter>,
  );

describe('NotificationsPage', () => {
  beforeEach(() => {
    notificationsMock.mockResolvedValue(page([unread, read]));
    readAllMock.mockResolvedValue({ updated: 1 });
  });

  it('渲染通知标题、跳转链接与已读/未读样式', async () => {
    renderPage();

    const link = await screen.findByRole('link', { name: '你的问题有新回答' });
    expect(link).toHaveAttribute('href', '/questions/9');
    expect(link.closest('li')).not.toHaveClass('muted');

    const readLink = screen.getByRole('link', { name: '获得「首次采纳」徽章' });
    expect(readLink).toHaveAttribute('href', '/users/9/reputation');
    expect(readLink.closest('li')).toHaveClass('muted');
  });

  it('没有通知时显示空态', async () => {
    notificationsMock.mockResolvedValue(page([]));
    renderPage();

    expect(await screen.findByText('暂时没有通知。')).toBeInTheDocument();
  });

  it('接口失败时保持空列表而不是把错误抛到渲染层', async () => {
    notificationsMock.mockRejectedValue(new Error('boom'));
    renderPage();

    expect(await screen.findByText('暂时没有通知。')).toBeInTheDocument();
  });

  it('点击「全部标记为已读」后重新拉取列表', async () => {
    notificationsMock
      .mockResolvedValueOnce(page([unread, read]))
      .mockResolvedValueOnce(page([{ ...unread, read: true }, read]));
    renderPage();
    await screen.findByRole('link', { name: '你的问题有新回答' });

    fireEvent.click(screen.getByRole('button', { name: '全部标记为已读' }));

    await waitFor(() => expect(readAllMock).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(notificationsMock).toHaveBeenCalledTimes(2));
    await waitFor(() =>
      expect(screen.getByRole('link', { name: '你的问题有新回答' }).closest('li')).toHaveClass('muted'),
    );
  });
});
