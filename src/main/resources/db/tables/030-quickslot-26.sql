-- Long keyboard (kaentake longkeyboard.cpp): the client quickslot grows from 8 to 26 keys, which no longer
-- fits the per-account keymap BIGINT. keys26 holds the 26 DIK scan codes; rows without it keep their stock
-- 8-key keymap, which Character expands on load (QuickslotBinding.fromLegacy).
ALTER TABLE quickslotkeymapped
    ADD COLUMN keys26 VARBINARY(26) NULL;
