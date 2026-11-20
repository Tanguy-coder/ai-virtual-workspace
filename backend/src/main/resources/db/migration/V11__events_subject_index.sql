-- The Lead's ladder counts its decisions on a task since the CTO's last intervention (docs/16): events by subject.
CREATE INDEX events_subject_seq_idx ON events (subject_id, seq);
