import type { DocumentRecord, KnowledgeBase } from '../../api/contracts';

const createdAt = '2026-09-20T08:30:00';
const updatedAt = '2026-09-20T10:45:00';

const initialKnowledgeBases: KnowledgeBase[] = [
  {
    id: 1,
    name: 'CO₂ 催化研究',
    description: '论文、实验记录和表征资料',
    createdAt,
    updatedAt,
  },
  {
    id: 2,
    name: 'Agent 平台设计',
    description: '需求、架构设计和接口契约',
    createdAt,
    updatedAt,
  },
];

const initialDocuments: Record<number, DocumentRecord[]> = {
  1: [
    {
      id: 101,
      knowledgeBaseId: 1,
      originalName: 'catalyst-paper.pdf',
      contentType: 'application/pdf',
      extension: 'pdf',
      sizeBytes: 1_854_320,
      sha256: 'd7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592',
      status: 'READY',
      errorCode: null,
      errorMessage: null,
      createdAt,
      updatedAt,
    },
    {
      id: 102,
      knowledgeBaseId: 1,
      originalName: 'failed-experiment.docx',
      contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      extension: 'docx',
      sizeBytes: 928_456,
      sha256: '307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592d7a8fbb3',
      status: 'FAILED',
      errorCode: 'DOCUMENT_PARSE_CORRUPTED_DOCUMENT',
      errorMessage: '文档已损坏或格式不正确',
      createdAt,
      updatedAt,
    },
  ],
  2: [],
};

let knowledgeBases: KnowledgeBase[];
let documentsByKnowledgeBase: Record<number, DocumentRecord[]>;
let nextKnowledgeBaseId: number;
let nextDocumentId: number;

export function resetFixtures() {
  knowledgeBases = structuredClone(initialKnowledgeBases);
  documentsByKnowledgeBase = structuredClone(initialDocuments);
  nextKnowledgeBaseId = 3;
  nextDocumentId = 103;
}

resetFixtures();

export function getKnowledgeBases() {
  return knowledgeBases;
}

export function getKnowledgeBase(knowledgeBaseId: number) {
  return knowledgeBases.find((knowledgeBase) => knowledgeBase.id === knowledgeBaseId);
}

export function addKnowledgeBase(input: { name: string; description?: string }) {
  const knowledgeBase: KnowledgeBase = {
    id: nextKnowledgeBaseId++,
    name: input.name.trim(),
    description: input.description?.trim() || null,
    createdAt: updatedAt,
    updatedAt,
  };
  knowledgeBases = [...knowledgeBases, knowledgeBase];
  documentsByKnowledgeBase[knowledgeBase.id] = [];
  return knowledgeBase;
}

export function updateKnowledgeBase(
  knowledgeBaseId: number,
  input: { name: string; description?: string },
) {
  const current = getKnowledgeBase(knowledgeBaseId);
  if (!current) {
    return undefined;
  }
  const next: KnowledgeBase = {
    ...current,
    name: input.name.trim(),
    description: input.description?.trim() || null,
    updatedAt,
  };
  knowledgeBases = knowledgeBases.map((knowledgeBase) =>
    knowledgeBase.id === knowledgeBaseId ? next : knowledgeBase,
  );
  return next;
}

export function removeKnowledgeBase(knowledgeBaseId: number) {
  knowledgeBases = knowledgeBases.filter((knowledgeBase) => knowledgeBase.id !== knowledgeBaseId);
  delete documentsByKnowledgeBase[knowledgeBaseId];
}

export function getDocuments(knowledgeBaseId: number) {
  return documentsByKnowledgeBase[knowledgeBaseId] ?? [];
}

export function getDocument(knowledgeBaseId: number, documentId: number) {
  return getDocuments(knowledgeBaseId).find((document) => document.id === documentId);
}

export function addDocument(knowledgeBaseId: number, file: File) {
  const extension = file.name.split('.').pop()?.toLowerCase() || 'txt';
  const document: DocumentRecord = {
    id: nextDocumentId++,
    knowledgeBaseId,
    originalName: file.name,
    contentType: file.type || 'text/plain',
    extension,
    sizeBytes: file.size,
    sha256: `mock-${file.name}-${file.size}`,
    status: 'UPLOADED',
    errorCode: null,
    errorMessage: null,
    createdAt: updatedAt,
    updatedAt,
  };
  documentsByKnowledgeBase[knowledgeBaseId] = [...getDocuments(knowledgeBaseId), document];
  return document;
}

export function updateDocument(
  knowledgeBaseId: number,
  documentId: number,
  patch: Partial<DocumentRecord>,
) {
  const current = getDocument(knowledgeBaseId, documentId);
  if (!current) {
    return undefined;
  }
  const next = { ...current, ...patch, updatedAt };
  documentsByKnowledgeBase[knowledgeBaseId] = getDocuments(knowledgeBaseId).map((document) =>
    document.id === documentId ? next : document,
  );
  return next;
}

export function removeDocument(knowledgeBaseId: number, documentId: number) {
  documentsByKnowledgeBase[knowledgeBaseId] = getDocuments(knowledgeBaseId).filter(
    (document) => document.id !== documentId,
  );
}
