-- kaentake DamageRank: bind F12 (scan code 88) to the Key Config shortcut type 4 / action 55 for every
-- existing character. New characters get it from GameConstants DEFAULT_*/CUSTOM_*. Characters that
-- already use F12 for something else are left alone.
INSERT INTO keymap (characterid, `key`, type, action)
SELECT c.id, 88, 4, 55
FROM characters c
WHERE NOT EXISTS (SELECT 1 FROM keymap k WHERE k.characterid = c.id AND k.`key` = 88);
