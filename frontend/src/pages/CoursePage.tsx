import { useEffect, useState } from 'react';
import { Table, Button, message, Tabs } from 'antd';
import { api } from '../api'; // 引入你刚才配好的接口

export default function CoursePage() {
    const [allData, setAllData] = useState<any[]>([]);
    const [myData, setMyData] = useState<any[]>([]);
    const [loading, setLoading] = useState(true);

    // 页面加载时拉取数据
    useEffect(() => {
        setLoading(true);
        // 拉取所有课程
        api.courses()
            .then((res: any) => setAllData(res?.items || res || []))
            .catch((err) => console.error("拉取课程失败", err))
            .finally(() => setLoading(false));

        // 拉取我的课程
        api.myCourses()
            .then((res: any) => setMyData(res || []))
            .catch((err) => console.error("拉取我的课程失败", err));
    }, []);

    // 点击加入课程
    const handleJoin = (courseId: number) => {
        api.joinCourse(courseId)
            .then(() => {
                message.success('加入课程成功！');
                // 简单粗暴地刷新页面
                window.location.reload();
            })
            .catch((err) => {
                message.error(err.message || '加入失败');
            });
    };

    const columns = [
        { title: '课程代码', dataIndex: 'code', key: 'code' },
        { title: '课程名称', dataIndex: 'name', key: 'name' },
        { title: '学期', dataIndex: 'term', key: 'term' },
        {
            title: '操作',
            key: 'action',
            render: (_: any, record: any) => (
                <Button type="primary" onClick={() => handleJoin(record.id)}>
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
                        children: <Table dataSource={allData} columns={columns} loading={loading} rowKey="id" />
                    },
                    {
                        key: 'my',
                        label: '我的课程',
                        children: <Table dataSource={myData} columns={columns.slice(0, 3)} loading={loading} rowKey="id" />
                    }
                ]}
            />
        </div>
    );
}