import { Button } from 'antd';
import { useRouteError } from 'react-router-dom';
import { ErrorResult } from '../components/feedback';

export function RouteErrorPage() {
  const error = useRouteError();
  const isNotFound =
    typeof error === 'object' &&
    error !== null &&
    'status' in error &&
    (error as { status?: number }).status === 404;

  return (
    <ErrorResult
      status={isNotFound ? '404' : 'error'}
      title={isNotFound ? '页面不存在' : '页面无法打开'}
      subTitle={isNotFound ? '请检查访问地址，或返回工作台继续操作。' : '请刷新页面后重试。'}
      action={
        <Button type="primary" href="/">
          返回工作台
        </Button>
      }
    />
  );
}
