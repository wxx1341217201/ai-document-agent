import {
  DashboardOutlined,
  DatabaseOutlined,
  ExperimentOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  RobotOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { Avatar, Breadcrumb, Button, Layout, Menu, Space, Tag, Tooltip, Typography } from 'antd';
import type { MenuProps } from 'antd';
import { useMemo } from 'react';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useUiStore } from '../../stores/ui.store';

const navigationItems: MenuProps['items'] = [
  { key: '/', icon: <DashboardOutlined />, label: '工作台' },
  { key: '/knowledge-bases', icon: <DatabaseOutlined />, label: '知识库' },
  { key: '/agent-executions', icon: <RobotOutlined />, label: 'Agent 任务' },
  { key: '/system', icon: <SettingOutlined />, label: '系统状态' },
];

export function AppLayout() {
  const location = useLocation();
  const navigate = useNavigate();
  const sidebarCollapsed = useUiStore((state) => state.sidebarCollapsed);
  const setSidebarCollapsed = useUiStore((state) => state.setSidebarCollapsed);
  const toggleSidebar = useUiStore((state) => state.toggleSidebar);
  const currentKnowledgeBase = useUiStore((state) => state.currentKnowledgeBase);

  const selectedKey = useMemo(() => selectNavigationKey(location.pathname), [location.pathname]);
  const breadcrumbItems = useMemo(
    () => createBreadcrumbItems(location.pathname, currentKnowledgeBase?.name),
    [currentKnowledgeBase?.name, location.pathname],
  );

  return (
    <Layout className="app-layout">
      <Layout.Sider
        className="app-sider"
        trigger={null}
        collapsible
        collapsed={sidebarCollapsed}
        breakpoint="lg"
        onBreakpoint={setSidebarCollapsed}
      >
        <Link className="brand" to="/" aria-label="返回工作台">
          <ExperimentOutlined />
          {!sidebarCollapsed ? <span>文档智能平台</span> : null}
        </Link>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey]}
          items={navigationItems}
          onClick={({ key }) => navigate(key)}
        />
      </Layout.Sider>

      <Layout>
        <Layout.Header className="app-header">
          <Button
            type="text"
            className="sidebar-toggle"
            aria-label={sidebarCollapsed ? '展开导航栏' : '收起导航栏'}
            icon={sidebarCollapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            onClick={toggleSidebar}
          />
          <Space className="header-meta" size="middle">
            <Typography.Text ellipsis className="current-kb">
              {currentKnowledgeBase ? `当前知识库：${currentKnowledgeBase.name}` : '未选择知识库'}
            </Typography.Text>
            <Tooltip title="系统健康接口将在后续模块接入">
              <Tag>系统状态待接入</Tag>
            </Tooltip>
            <Avatar aria-label="用户功能预留">预</Avatar>
          </Space>
        </Layout.Header>

        <Layout.Content className="app-content">
          <Breadcrumb items={breadcrumbItems} />
          <main className="page-content">
            <Outlet />
          </main>
        </Layout.Content>
      </Layout>
    </Layout>
  );
}

function selectNavigationKey(pathname: string): string {
  if (pathname.startsWith('/knowledge-bases')) {
    return '/knowledge-bases';
  }
  if (pathname.startsWith('/agent-executions')) {
    return '/agent-executions';
  }
  if (pathname.startsWith('/system')) {
    return '/system';
  }
  return '/';
}

function createBreadcrumbItems(pathname: string, knowledgeBaseName?: string) {
  const items: { title: string }[] = [{ title: '工作台' }];
  if (pathname.startsWith('/knowledge-bases')) {
    items.push({ title: '知识库' });
    if (/^\/knowledge-bases\/[^/]+/.test(pathname)) {
      items.push({ title: knowledgeBaseName || '知识库详情' });
    }
    if (/\/documents\//.test(pathname)) {
      items.push({ title: '文档详情' });
    }
  } else if (pathname.startsWith('/agent-executions')) {
    items.push({ title: 'Agent 任务' });
  } else if (pathname.startsWith('/system')) {
    items.push({ title: '系统状态' });
  }
  return items;
}
