import { BugOutlined } from '@ant-design/icons';
import { Button, Result } from 'antd';
import { Component, type ErrorInfo, type ReactNode } from 'react';

interface ErrorBoundaryProps {
  children: ReactNode;
}

interface ErrorBoundaryState {
  hasError: boolean;
}

export class AppErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { hasError: false };

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { hasError: true };
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    if (import.meta.env.DEV) {
      console.error('应用渲染失败', error, errorInfo);
    }
  }

  render() {
    if (this.state.hasError) {
      return (
        <Result
          status="500"
          icon={<BugOutlined />}
          title="页面出现了意外问题"
          subTitle="请刷新页面后重试；如问题持续出现，请联系管理员。"
          extra={
            <Button type="primary" onClick={() => window.location.reload()}>
              刷新页面
            </Button>
          }
        />
      );
    }

    return this.props.children;
  }
}
