import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { StatusTag } from './index';

describe('StatusTag', () => {
  it('renders a textual status in addition to its color', () => {
    render(<StatusTag status="PROCESSING" />);

    expect(screen.getByRole('status')).toHaveTextContent('处理中');
    expect(screen.getByRole('status')).toHaveAttribute('aria-label', '状态：处理中');
  });
});
