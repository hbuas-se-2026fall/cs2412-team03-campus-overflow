import { useCallback, useEffect, useState } from 'react';
import { Table, Button, message, Tabs } from 'antd';
import { api } from '../api';

interface CourseRow {
  id: number;
  code: string;
  name: string;
  term: string;
}

/** 后端分页接口返回 `{ items, total }`，老接口直接返回数组，两种都要兼容。 */
function rows(value: unknown): CourseRow[] {
  const page = value as { items?: CourseRow[] } | undefined;
  return page?.items ?? ((value as CourseRow[]) || []);
}

/**
 * 课程中心：所有课程 / 我的课程。
 * <p>
 * 加入课程成功后**重新拉取两个列表**，而不是 `window.location.reload()`——
 * 整页刷新会丢掉当前 Tab、滚动位置与 antd 的组件状态，在答辩演示时尤其难看。
 * 拉取逻辑抽成 `fetchAll()` 也让「首次进入」与「加入后刷新」走同一条路径，避免两处逻辑漂移。
 */
export default function CoursePage() {
  const [allData, setAllData] = useState<CourseRow[]>([]);
  const [myData, setMyData] = useState<CourseRow[]>([]);
  const [loading, setLoading] = useState(true);

  const fetchAll = useCallback(
    () =>
      Promise.all([api.courses(), api.myCourses()])
        .then(([all, mine]) => {
          setAllData(rows(all));
          setMyData(rows(mine));
        })
        .catch((err) => {
          console.error('拉取课程失败', err);
          setAllData([]);
          setMyData([]);
        })
        .finally(() => setLoading(false)),
    [],
  );

  useEffect(() => {
    void fetchAll();
  }, [fetchAll]);

  const handleJoin = async (courseId: number) => {
    try {
      await api.joinCourse(courseId);
      message.success('加入课程成功！');
      setLoading(true);
      await fetchAll();
    } catch (err) {
      message.error((err as Error).message || '加入失败');
    }
  };

  const columns = [
    { title: '课程代码', dataIndex: 'code', key: 'code' },
    { title: '课程名称', dataIndex: 'name', key: 'name' },
    { title: '学期', dataIndex: 'term', key: 'term' },
    {
      title: '操作',
      key: 'action',
      render: (_: unknown, record: CourseRow) => (
        <Button type="primary" onClick={() => void handleJoin(record.id)}>
          加入课程
        </Button>
      ),
    },
  ];

  return (
    <div style={{ padding: 24 }}>
      <h2>课程中心</h2>
      <Tabs
        items={[
          {
            key: 'all',
            label: '所有课程',
            children: <Table<CourseRow> dataSource={allData} columns={columns} loading={loading} rowKey="id" />,
          },
          {
            key: 'my',
            label: '我的课程',
            children: (
              <Table<CourseRow> dataSource={myData} columns={columns.slice(0, 3)} loading={loading} rowKey="id" />
            ),
          },
        ]}
      />
    </div>
  );
}
