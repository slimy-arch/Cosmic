-- NX uncap: NX Credit up to GameConstants.MAX_NX_CREDIT (9,999,999,999,999), the same ceiling as mesos
ALTER TABLE accounts
    MODIFY nxCredit BIGINT DEFAULT NULL;
