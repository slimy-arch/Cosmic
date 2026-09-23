-- Level cap 500: per-level EXP exceeds INT from level 205 (see kaentake/tools/gen_exptable.py)
ALTER TABLE characters
    MODIFY exp BIGINT NOT NULL DEFAULT 0;

ALTER TABLE characterexplogs
    MODIFY current_exp BIGINT;
