-- Damage skins (Kaentake damageskin.cpp): per-character applied skin, owned skins, and the shop catalog.
-- Skin 0 is the stock digits; every character implicitly owns it and it never has an inventory row.
ALTER TABLE characters
    ADD COLUMN activeDamageSkin INT NOT NULL DEFAULT 0;

CREATE TABLE damageskin_catalog
(
    skinId     INT    NOT NULL,
    priceMesos BIGINT NOT NULL DEFAULT 10000000,
    PRIMARY KEY (skinId)
);

CREATE TABLE damageskin_inventory
(
    id          INT       NOT NULL AUTO_INCREMENT,
    characterId INT       NOT NULL,
    skinId      INT       NOT NULL,
    acquiredAt  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_char_skin (characterId, skinId),
    CONSTRAINT fk_damageskin_char FOREIGN KEY (characterId) REFERENCES characters (id) ON DELETE CASCADE
);
