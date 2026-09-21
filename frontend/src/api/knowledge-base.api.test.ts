import { describe, expect, it } from 'vitest';
import { knowledgeBaseApi } from './knowledge-base.api';

describe('knowledgeBaseApi', () => {
  it('unwraps the backend response envelope', async () => {
    const response = await knowledgeBaseApi.list({ page: 0, size: 20 });

    expect(response.totalElements).toBe(2);
    expect(response.content[0]).toMatchObject({ id: 1, name: 'CO₂ 催化研究' });
  });

  it('normalizes a business conflict', async () => {
    await expect(knowledgeBaseApi.create({ name: 'CO₂ 催化研究' })).rejects.toMatchObject({
      code: 'KNOWLEDGE_BASE_NAME_CONFLICT',
      httpStatus: 409,
      message: '知识库名称已存在',
    });
  });
});
