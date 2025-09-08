PALETTE = [[0x40, 0x40, 0x40] for _ in range(64)]
PALETTE[1] = [0x9B, 0xEC, 0x77]  # grass / green
PALETTE[2] = [0x79, 0xD4, 0x5C]  # leaves
PALETTE[3] = [0x89, 0xB9, 0xCD]  # water
PALETTE[4] = [0xF5, 0xF5, 0xF5]  # quartz / white / snow
PALETTE[5] = [0xA5, 0xA5, 0xA5]  # stone / gray
PALETTE[6] = [0xF8, 0x92, 0x21]  # lava


def resolve_block(name: str) -> int:
    """Map a block id to a palette index using template rules."""
    block_id = name.split(":")[-1].lower()
    if (
        block_id == "grass_block"
        or "tall_grass" in block_id
        or "grass" in block_id
        or "green" in block_id
    ):
        return 1
    if "leaves" in block_id:
        return 2
    if "water" in block_id:
        return 3
    if "quartz" in block_id or "white" in block_id or "snow" in block_id:
        return 4
    if "stone" in block_id or "gray" in block_id:
        return 5
    if "lava" in block_id:
        return 6
    return 0
