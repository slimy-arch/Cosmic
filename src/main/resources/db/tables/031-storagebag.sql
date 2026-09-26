-- Storage Bag (kaentake storagebag.cpp): one per-account, per-world bag header shared by four tabs, and a
-- per-character auto-collect toggle for each tab. Item rows live in the stock inventoryitems table, keyed
-- by this storageid in the accountid column, with type 10 ore / 11 scroll / 12 chair / 13 cash
-- (ItemFactory OREBAG..CASHBAG).
CREATE TABLE IF NOT EXISTS orestorages (
    storageid   INT UNSIGNED NOT NULL AUTO_INCREMENT,
    accountid   INT          NOT NULL DEFAULT 0,
    world       INT          NOT NULL,
    slots       SMALLINT     NOT NULL DEFAULT 200,  -- ore tab capacity
    meso        INT          NOT NULL DEFAULT 0,
    scrollSlots SMALLINT     NOT NULL DEFAULT 200,
    chairSlots  SMALLINT     NOT NULL DEFAULT 200,
    cashSlots   SMALLINT     NOT NULL DEFAULT 200,
    PRIMARY KEY (storageid),
    KEY accountid_world (accountid, world)
);

ALTER TABLE characters
    ADD COLUMN autoOreStorage    TINYINT UNSIGNED NOT NULL DEFAULT 0,
    ADD COLUMN autoScrollStorage TINYINT UNSIGNED NOT NULL DEFAULT 0,
    ADD COLUMN autoChairStorage  TINYINT UNSIGNED NOT NULL DEFAULT 0,
    ADD COLUMN autoCashStorage   TINYINT UNSIGNED NOT NULL DEFAULT 0;
