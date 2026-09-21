import { Flex, Typography } from 'antd';
import type { ReactNode } from 'react';

export function PageHeader({
  title,
  description,
  actions,
}: {
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <Flex className="page-header" justify="space-between" align="flex-start" gap="middle" wrap>
      <div>
        <Typography.Title level={2}>{title}</Typography.Title>
        {description ? (
          <Typography.Paragraph type="secondary">{description}</Typography.Paragraph>
        ) : null}
      </div>
      {actions ? (
        <Flex gap="small" wrap>
          {actions}
        </Flex>
      ) : null}
    </Flex>
  );
}
