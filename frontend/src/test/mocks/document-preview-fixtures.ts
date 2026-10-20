import type { DocumentChunk } from '../../api/contracts';

/** Explicit synthetic chunks, deliberately nonconsecutive IDs. Never source real citations here. */
export const mockDocumentChunks: DocumentChunk[] = [
  {
    chunkId: 997,
    chunkIndex: 0,
    content: 'Mock 前文：任务可能发生中断。',
    pageFrom: 1,
    pageTo: 1,
    sectionTitle: '背景',
  },
  {
    chunkId: 1001,
    chunkIndex: 1,
    content: 'Mock 引用：幂等键避免重复处理。',
    pageFrom: 1,
    pageTo: 1,
    sectionTitle: '幂等与恢复',
  },
  {
    chunkId: 1059,
    chunkIndex: 2,
    content: 'Mock 后文：恢复时核对服务端状态。',
    pageFrom: 2,
    pageTo: 2,
    sectionTitle: null,
  },
];

/** Small valid ASCII PDF for browser preview/download tests. */
export function createMockPdf() {
  const stream = 'BT /F1 16 Tf 40 100 Td (Mock document preview) Tj ET';
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`,
  ];
  let pdf = '%PDF-1.4\n';
  const offsets = [0];
  objects.forEach((body, index) => {
    offsets.push(pdf.length);
    pdf += `${index + 1} 0 obj\n${body}\nendobj\n`;
  });
  const xref = pdf.length;
  pdf += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  offsets.slice(1).forEach((offset) => {
    pdf += `${String(offset).padStart(10, '0')} 00000 n \n`;
  });
  return pdf + `trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
}
