-- A person's correction of an entry's title, kept beside the title Rivell read (raw_label), as
-- edited_content_text is kept beside content_text.
alter table knowledge_candidate add column edited_title text;

-- The title a Word source gives itself, read at upload. Null for a workbook or a document that
-- names none; the file name is then the only name.
alter table knowledge_artifact add column document_title text;
