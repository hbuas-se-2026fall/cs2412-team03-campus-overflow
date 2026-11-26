import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CoursePage from './CoursePage';

const { coursesMock, myCoursesMock, joinCourseMock } = vi.hoisted(() => ({
  coursesMock: vi.fn(),
  myCoursesMock: vi.fn(),
  joinCourseMock: vi.fn(),
}));

vi.mock('../api', () => ({
  api: {
    courses: coursesMock,
    myCourses: myCoursesMock,
    joinCourse: joinCourseMock,
  },
}));

const cs101 = { id: 1, code: 'CS101', name: '数据结构与算法', term: '2026秋' };
const cs102 = { id: 2, code: 'CS102', name: '操作系统', term: '2026秋' };

const page = (items: (typeof cs101)[]) => ({
  items,
  total: items.length,
  page: 1,
  size: 20,
});

describe('CoursePage', () => {
  beforeEach(() => {
    // antd 的响应式栅格会读 matchMedia，jsdom 没有实现
    Object.defineProperty(window, 'matchMedia', {
      writable: true,
      value: (query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addListener: vi.fn(),
        removeListener: vi.fn(),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        dispatchEvent: vi.fn(),
      }),
    });
    coursesMock.mockResolvedValue(page([cs101, cs102]));
    myCoursesMock.mockResolvedValue([cs101]);
    joinCourseMock.mockResolvedValue(undefined);
  });

  it('渲染课程中心的两个 Tab 与课程列表', async () => {
    render(<CoursePage />);

    expect(
      screen.getByRole('heading', { name: '课程中心' })
    ).toBeInTheDocument();
    expect(await screen.findByText('CS101')).toBeInTheDocument();
    expect(screen.getByText('CS102')).toBeInTheDocument();
    expect(screen.getAllByRole('tab').length).toBe(2);
    expect(coursesMock).toHaveBeenCalledTimes(1);
    expect(myCoursesMock).toHaveBeenCalledTimes(1);
  });

  it('加入课程成功后重新拉取两个列表，而不是整页刷新', async () => {
    render(<CoursePage />);
    await screen.findByText('CS101');

    const reload = vi.fn();
    const originalLocation = window.location;
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...originalLocation, reload },
    });

    fireEvent.click(screen.getAllByRole('button', { name: '加入课程' })[0]);

    await waitFor(() => expect(joinCourseMock).toHaveBeenCalledWith(1));
    await waitFor(() => expect(coursesMock).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(myCoursesMock).toHaveBeenCalledTimes(2));
    expect(reload).not.toHaveBeenCalled();

    Object.defineProperty(window, 'location', {
      configurable: true,
      value: originalLocation,
    });
  });

  it('加入课程失败时不会重复拉取列表', async () => {
    joinCourseMock.mockRejectedValue(new Error('你已加入该课程'));
    render(<CoursePage />);
    await screen.findByText('CS101');

    fireEvent.click(screen.getAllByRole('button', { name: '加入课程' })[0]);

    await waitFor(() => expect(joinCourseMock).toHaveBeenCalledTimes(1));
    expect(coursesMock).toHaveBeenCalledTimes(1);
    expect(myCoursesMock).toHaveBeenCalledTimes(1);
  });

  it('接口失败时降级为空表而不是崩溃', async () => {
    coursesMock.mockRejectedValue(new Error('boom'));
    myCoursesMock.mockRejectedValue(new Error('boom'));
    const spy = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    render(<CoursePage />);

    await waitFor(() => expect(coursesMock).toHaveBeenCalled());
    expect(screen.queryByText('CS101')).not.toBeInTheDocument();
    expect(
      screen.getByRole('columnheader', { name: '课程代码' })
    ).toBeInTheDocument();
    spy.mockRestore();
  });

  it('分页数据带 items 时按 items 渲染', async () => {
    coursesMock.mockResolvedValue(page([cs102]));
    myCoursesMock.mockResolvedValue(page([cs102]) as never);

    render(<CoursePage />);

    expect(await screen.findByText('CS102')).toBeInTheDocument();
    expect(screen.queryByText('CS101')).not.toBeInTheDocument();
  });
});
