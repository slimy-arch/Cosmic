-- kaentake Storage Bag: bind F9 (scan code 67) to the Key Config shortcut type 4 / action 56 for every
-- existing character. New characters get it from GameConstants DEFAULT_*/CUSTOM_*. Characters that
-- already use F9 for something else are left alone.
INSERT INTO keymap (characterid, `key`, type, action)
SELECT c.id, 67, 4, 56
FROM characters c
WHERE NOT EXISTS (SELECT 1 FROM keymap k WHERE k.characterid = c.id AND k.`key` = 67);
