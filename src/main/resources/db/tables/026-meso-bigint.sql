-- Meso uncap: wallet, storage and Fredrick/Hired Merchant balances up to GameConstants.MAX_MESO (9,999,999,999,999)
ALTER TABLE characters
    MODIFY meso BIGINT NOT NULL DEFAULT 0,
    MODIFY MerchantMesos BIGINT DEFAULT 0;

ALTER TABLE storages
    MODIFY meso BIGINT NOT NULL DEFAULT 0;
