from mca_import import PaletteClient


def test_palette_client_local():
    client = PaletteClient()
    data = client.palette()
    assert len(data["palette"]) == 64
    indices = list(client.resolve(["minecraft:grass_block", "minecraft:stone"]))
    assert indices == [1, 0]
