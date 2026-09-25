-- Preserve historical PDF and HTML schemas; add immutable CMAS result JSON observations.
ALTER TABLE freediving.extractions DROP CONSTRAINT extractions_schema_version_check;
ALTER TABLE freediving.extractions ADD CONSTRAINT extractions_schema_version_check
 CHECK (schema_version IN (1,2,3,4,5));
