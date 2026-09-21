import type { DocumentStatus } from '../api/contracts';

const inFlightStatuses: ReadonlySet<DocumentStatus> = new Set([
  'UPLOADED',
  'QUEUED',
  'PROCESSING',
  'RETRYING',
]);

export function isDocumentProcessing(status: DocumentStatus): boolean {
  return inFlightStatuses.has(status);
}

export function isDocumentTerminal(status: DocumentStatus): boolean {
  return status === 'READY' || status === 'FAILED' || status === 'DELETED';
}

export function hasInFlightDocuments(statuses: DocumentStatus[]): boolean {
  return statuses.some(isDocumentProcessing);
}
