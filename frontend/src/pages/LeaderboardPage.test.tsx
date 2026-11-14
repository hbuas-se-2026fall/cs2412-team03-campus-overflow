import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { LeaderboardPage } from './LeaderboardPage';

const { leaderboardMock } = vi.hoisted(() => ({ leaderboardMock: vi.fn() }));

vi.mock('../api', () => ({ api: { leaderboard: leaderboardMock } }));

const rows = [
  { rank: 1, userId: 9, displayName: '刘浩然', verified: true, score: 120 },
  { rank: 2, userId: 12, displayName: '易子恒', verified: false, score: 88 },
];

describe('LeaderboardPage', () => {
  beforeEach(() => {
    leaderboardMock.mockResolvedValue(rows);
  });

  it('默认按本周拉取，并把名次 / 用户名 / 声誉渲染成表格行', async () => {
    render(<LeaderboardPage />);

    expect(screen.getByRole('heading', { name: '声誉排行榜' })).toBeInTheDocument();
    expect(await screen.findByText('刘浩然')).toBeInTheDocument();
    expect(screen.getByText('易子恒')).toBeInTheDocument();
    expect(screen.getByText('120')).toBeInTheDocument();
    expect(screen.getByText('88')).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument();
    expect(leaderboardMock).toHaveBeenCalledWith('week');
  });

  it('只有已认证用户显示认证标记', async () => {
    render(<LeaderboardPage />);
    await screen.findByText('刘浩然');

    expect(screen.getAllByTitle('已认证')).toHaveLength(1);
  });

  it('切换周期会重新拉数据，并把按钮标成 active', async () => {
    render(<LeaderboardPage />);
    await screen.findByText('刘浩然');

    const monthTab = screen.getByRole('button', { name: '本月' });
    fireEvent.click(monthTab);

    await waitFor(() => expect(leaderboardMock).toHaveBeenLastCalledWith('month'));
    expect(monthTab).toHaveClass('active');
    expect(screen.getByRole('button', { name: '本周' })).not.toHaveClass('active');
  });

  it('接口返回空数组时给出空态文案', async () => {
    leaderboardMock.mockResolvedValue([]);
    render(<LeaderboardPage />);

    expect(await screen.findByText('这个周期还没有数据。')).toBeInTheDocument();
  });

  it('接口失败时降级为空表，不把错误抛到渲染层', async () => {
    leaderboardMock.mockRejectedValue(new Error('network down'));
    render(<LeaderboardPage />);

    expect(await screen.findByText('这个周期还没有数据。')).toBeInTheDocument();
    expect(screen.queryByText('刘浩然')).not.toBeInTheDocument();
  });
});
