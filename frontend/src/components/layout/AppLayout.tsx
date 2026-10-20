import {
  BookOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  MenuFoldOutlined,
  MenuOutlined,
  MenuUnfoldOutlined,
  RobotOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { Breadcrumb, Button, Drawer, Dropdown, Grid, Layout, Menu, Tooltip } from 'antd';
import type { MenuProps } from 'antd';
import type { ReactNode } from 'react';
import { useMemo, useState } from 'react';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useUiStore } from '../../stores/ui.store';

const navigationItems: MenuProps['items'] = [
  { key: '/', icon: <DashboardOutlined />, label: '工作台' },
  { key: '/knowledge-bases', icon: <DatabaseOutlined />, label: '知识库' },
  { key: '/reading', icon: <BookOutlined />, label: '阅读笔记' },
];

const diagnosticItems: MenuProps['items'] = [
  { key: '/agent-executions', icon: <RobotOutlined />, label: '任务记录' },
  { key: '/system', icon: <SettingOutlined />, label: '系统诊断' },
];

export function AppLayout() {
  const location = useLocation();
  const navigate = useNavigate();
  const screens = Grid.useBreakpoint();
  const isMobile = screens.md === false;
  const [mobileNavigationOpen, setMobileNavigationOpen] = useState(false);
  const sidebarCollapsed = useUiStore((state) => state.sidebarCollapsed);
  const setSidebarCollapsed = useUiStore((state) => state.setSidebarCollapsed);
  const toggleSidebar = useUiStore((state) => state.toggleSidebar);
  const currentKnowledgeBase = useUiStore((state) => state.currentKnowledgeBase);

  const selectedKey = selectNavigationKey(location.pathname);
  const breadcrumbItems = useMemo(
    () => createBreadcrumbItems(location.pathname, currentKnowledgeBase?.name),
    [currentKnowledgeBase?.name, location.pathname],
  );

  function navigation(collapsed = false) {
    return (
      <div className={'sidebar-content' + (collapsed ? ' sidebar-content-collapsed' : '')}>
        <Link
          className="brand"
          to="/"
          aria-label="返回工作台"
          onClick={() => setMobileNavigationOpen(false)}
        >
          <span className="brand-mark" aria-hidden="true">
            <BookOutlined />
          </span>
          {!collapsed ? (
            <span className="brand-wordmark">
              文档智能<span>DOCUMENT AGENT</span>
            </span>
          ) : null}
        </Link>
        <nav aria-label="主导航">
          {!collapsed ? <span className="navigation-caption">工作空间</span> : null}
          <Menu
            mode="inline"
            inlineCollapsed={collapsed}
            selectedKeys={[selectedKey]}
            items={navigationItems}
            onClick={({ key }) => {
              navigate(key);
              setMobileNavigationOpen(false);
            }}
          />
        </nav>
        <div className="sidebar-bottom">
          <Dropdown
            trigger={['click']}
            placement="topLeft"
            menu={{
              items: diagnosticItems,
              onClick: ({ key }) => {
                navigate(key);
                setMobileNavigationOpen(false);
              },
            }}
          >
            <Button
              type="text"
              className="sidebar-tools-button"
              block={!collapsed}
              icon={<SettingOutlined />}
              aria-label="高级工具"
            >
              {collapsed ? null : '高级工具'}
            </Button>
          </Dropdown>
          {!collapsed ? (
            <div className="workspace-identity" aria-label="当前工作空间">
              <span className="workspace-monogram" aria-hidden="true">
                D
              </span>
              <span>
                文档智能工作空间<small>留一点空间，给思考。</small>
              </span>
            </div>
          ) : null}
        </div>
      </div>
    );
  }

  return (
    <Layout className="app-layout">
      <a className="skip-link" href="#main-content">
        跳转到主要内容
      </a>
      <Layout.Sider
        className="app-sider"
        width={232}
        collapsedWidth={isMobile ? 0 : 76}
        trigger={null}
        collapsible
        collapsed={isMobile || sidebarCollapsed}
        breakpoint="lg"
        onBreakpoint={setSidebarCollapsed}
      >
        {isMobile ? null : navigation(sidebarCollapsed)}
      </Layout.Sider>

      <Layout className="app-main-layout">
        <Layout.Header className="app-header">
          <div className="header-navigation">
            <Tooltip
              title={isMobile ? '打开导航栏' : sidebarCollapsed ? '展开导航栏' : '收起导航栏'}
            >
              <Button
                type="text"
                className="sidebar-toggle"
                aria-label={
                  isMobile ? '打开导航栏' : sidebarCollapsed ? '展开导航栏' : '收起导航栏'
                }
                aria-expanded={isMobile ? mobileNavigationOpen : !sidebarCollapsed}
                icon={
                  isMobile ? (
                    <MenuOutlined />
                  ) : sidebarCollapsed ? (
                    <MenuUnfoldOutlined />
                  ) : (
                    <MenuFoldOutlined />
                  )
                }
                onClick={isMobile ? () => setMobileNavigationOpen(true) : toggleSidebar}
              />
            </Tooltip>
            <span className="header-divider" aria-hidden="true" />
            <Breadcrumb items={breadcrumbItems} />
          </div>
          <div className="header-meta">
            <span className="current-kb">{currentKnowledgeBase?.name ?? '文档智能工作空间'}</span>
          </div>
        </Layout.Header>

        <div className="app-content">
          <main id="main-content" className="page-content" tabIndex={-1}>
            <Outlet />
          </main>
        </div>
      </Layout>
      <Drawer
        title="导航"
        className="mobile-navigation"
        placement="left"
        width={280}
        open={mobileNavigationOpen && isMobile}
        onClose={() => setMobileNavigationOpen(false)}
      >
        {navigation()}
      </Drawer>
    </Layout>
  );
}

function selectNavigationKey(pathname: string): string {
  if (pathname === '/reading' || /\/reading(?:\/|$)/.test(pathname)) return '/reading';
  if (pathname.startsWith('/knowledge-bases')) return '/knowledge-bases';
  if (pathname.startsWith('/agent-executions')) return '/agent-executions';
  if (pathname.startsWith('/system')) return '/system';
  return '/';
}

function createBreadcrumbItems(pathname: string, knowledgeBaseName?: string) {
  const items: { title: ReactNode }[] = [
    { title: pathname === '/' ? '工作台' : <Link to="/">工作台</Link> },
  ];
  if (pathname.startsWith('/knowledge-bases')) {
    items.push({
      title: pathname === '/knowledge-bases' ? '知识库' : <Link to="/knowledge-bases">知识库</Link>,
    });
    if (/^\/knowledge-bases\/[^/]+/.test(pathname))
      items.push({ title: knowledgeBaseName || '知识库详情' });
    if (/\/documents\//.test(pathname)) items.push({ title: '文档详情' });
    if (/\/chat(?:\/|$)/.test(pathname)) items.push({ title: '智能问答' });
    if (/\/reading(?:\/|$)/.test(pathname)) items.push({ title: '主题阅读' });
  } else if (pathname === '/reading') {
    items.push({ title: '阅读笔记' });
  } else if (pathname.startsWith('/agent-executions')) {
    items.push({ title: '任务记录' });
    if (pathname !== '/agent-executions') items.push({ title: '执行详情' });
  } else if (pathname.startsWith('/system')) {
    items.push({ title: '系统诊断' });
  }
  return items;
}
