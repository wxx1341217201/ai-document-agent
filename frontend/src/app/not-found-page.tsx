import { Button } from 'antd';
import { ErrorResult } from '../components/feedback';

export function NotFoundPage() {
  return (
    <ErrorResult
      status="404"
      title="页面不存在"
      subTitle="请检查访问地址，或返回工作台继续操作。"
      action={
        <Button type="primary" href="/">
          返回工作台
        </Button>
      }
    />
  );
}
