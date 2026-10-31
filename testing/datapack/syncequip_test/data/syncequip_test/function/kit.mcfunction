# Gives the runner a fresh test kit with all equipment slots empty.
clear @s
item replace entity @s armor.head with minecraft:air
item replace entity @s armor.chest with minecraft:air
item replace entity @s armor.legs with minecraft:air
item replace entity @s armor.feet with minecraft:air
item replace entity @s weapon.offhand with minecraft:air
item replace entity @s hotbar.0 with minecraft:netherite_sword
item replace entity @s inventory.0 with minecraft:netherite_boots
item replace entity @s inventory.1 with minecraft:netherite_leggings
item replace entity @s inventory.2 with minecraft:netherite_chestplate
item replace entity @s inventory.3 with minecraft:netherite_helmet
item replace entity @s inventory.4 with minecraft:shield
tellraw @s {"text":"Test kit ready. Keep slot 1 selected and run /syncequip solo.","color":"green"}
