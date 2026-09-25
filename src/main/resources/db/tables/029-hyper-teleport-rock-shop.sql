-- Hyper Teleport Rock (5590001) in the GM shop (1337) for testing. A shop purchase has no expiration,
-- so this copy is permanent; timed copies come from the Cash Shop or an expiring grant.
INSERT INTO shopitems (shopid, itemid, price, pitch, position)
SELECT 1337, 5590001, 1, 0, 87
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM shopitems WHERE shopid = 1337 AND itemid = 5590001);
