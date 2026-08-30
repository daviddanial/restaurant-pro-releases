#!/usr/bin/env bash
# Remove a study and everything hanging off it so the same DICOM set can be
# re-uploaded as a fresh order. Ingest matches on StudyInstanceUID and will not
# regress an IN_PROGRESS study back to READY, so a re-upload without this is a
# no-op for workflow testing.
#
#   ./reset_study.sh                        # list studies
#   ./reset_study.sh <study-uuid>           # delete one study
#   ./reset_study.sh --all                  # delete every study and patient
set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:-teleradiology-postgres-1}"
PG_USER="${PG_USER:-telerad}"
PG_DB="${PG_DB:-telerad}"

psql_run() {
  docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" "$@"
}

if [[ $# -eq 0 ]]; then
  echo "Studies currently in the database:"
  psql_run -c "select s.id, s.modality, s.status, s.study_description,
                      (select count(*) from series se where se.study_id = s.id) as series,
                      s.instance_count
               from studies s order by s.created_at desc;"
  echo
  echo "Usage: $0 <study-uuid> | --all"
  exit 0
fi

if [[ "$1" == "--all" ]]; then
  WHERE="true"
  LABEL="all studies"
else
  WHERE="s.id = '$1'"
  LABEL="study $1"
fi

echo "Deleting $LABEL ..."
psql_run <<SQL
BEGIN;
DELETE FROM report_deliveries WHERE report_id IN (
  SELECT r.id FROM reports r JOIN studies s ON r.study_id = s.id WHERE $WHERE
);
DELETE FROM reports WHERE study_id IN (SELECT s.id FROM studies s WHERE $WHERE);
DELETE FROM instances WHERE series_id IN (
  SELECT se.id FROM series se JOIN studies s ON se.study_id = s.id WHERE $WHERE
);
DELETE FROM series WHERE study_id IN (SELECT s.id FROM studies s WHERE $WHERE);
DELETE FROM studies s WHERE $WHERE;
DELETE FROM patients p WHERE NOT EXISTS (
  SELECT 1 FROM studies s2 WHERE s2.patient_id = p.id
);
COMMIT;
SQL

echo "Done. Remaining studies:"
psql_run -c "select id, modality, status, study_description from studies order by created_at desc;"
echo
echo "Note: DICOM objects stay in MinIO. They are orphaned and harmless, but you"
echo "can clear the bucket from the console at http://localhost:9001 if you want."
