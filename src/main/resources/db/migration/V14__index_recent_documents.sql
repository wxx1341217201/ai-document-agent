-- The dashboard reads only the five most recently updated documents.
CREATE INDEX idx_document_updated_at_id ON document (updated_at, id);
