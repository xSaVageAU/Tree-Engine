# Tree Feature Configuration Reference

This document describes the **JSON/NBT structure** used by Minecraft's `minecraft:tree` feature.  It lists every field you can set, their types, defaults, and provides a couple of concrete examples you can copy‑paste into a datapack.

---

## Top‑Level Feature Object
```json
{
  "type": "minecraft:tree",
  "config": { /* see below */ }
}
```
* `type` – always `"minecraft:tree"`.
* `config` – the object that contains all tree‑specific settings.

---

## `config` Object – Fields
| Field | Type | Required? | Default | Description |
|-------|------|-----------|---------|-------------|
| `ignore_vines` | Boolean | No | `false` | Generate even if vines would block foliage. |
| `force_dirt` | Boolean | No | `false` | Force placement of `dirt_provider` beneath the trunk. |
| `dirt_provider` | BlockStateProvider (compound) | No (only needed when `force_dirt` is true or no valid dirt below) | – | Block placed under the trunk. |
| `trunk_provider` | BlockStateProvider (compound) | **Yes** | – | Block used for the trunk (must have `axis` for fancy placers). |
| `foliage_provider` | BlockStateProvider (compound) | **Yes** | – | Block used for leaves. |
| `minimum_size` | TreeFeatureSize (compound) | **Yes** | – | Determines the minimum width/height of the tree. |
| `trunk_placer` | TrunkPlacer (compound) | **Yes** | – | Controls trunk generation. |
| `foliage_placer` | FoliagePlacer (compound) | **Yes** | – | Controls foliage generation. |
| `root_placer` | RootPlacer (compound) | No | – | Optional root generation. |
| `decorators` | List of Decorator objects | **Yes** (may be empty) | `[]` | Extra decorations (vines, beehives, cocoa, …). |
| `heightmap` | String | No | – | Heightmap name to anchor the tree (rarely needed). |

---

## Block State Provider
All provider objects share the same top‑level field:
```json
{ "type": "minecraft:<provider_type>", ... }
```
### Common provider types
| Provider | Required fields | Optional / extra fields |
|----------|----------------|--------------------------|
| `simple_state_provider` | `state` (object with `Name` and optional `Properties`) | – |
| `weighted_state_provider` | `weighted_states` (array of `{ "weight": int, "state": {...}`) | – |
| `noise_threshold_provider` | `threshold`, `above`, `below` (each a state) | – |
| `dual_noise_provider` | `first_noise`, `second_noise`, `first_state`, `second_state` | – |
| `rotated_block_provider` | `state`, `rotation` (object) | – |

*See the Minecraft Wiki for the full list of provider‑specific fields.*

---

## Tree Feature Size (`minimum_size`)
```json
{ "type": "minecraft:<size_type>", ... }
```
| Size type | Fields |
|-----------|--------|
| `two_layers_feature_size` | `base_height`, `first_random_height`, `second_random_height` |
| `blob_feature_size` | `radius`, `height` |
| `mega_blob_feature_size` | `radius`, `height` |
| `random_spread_feature_size` | `base_height`, `first_random_height`, `second_random_height` |
| `large_blob_feature_size` | `radius`, `height` |

---

## Trunk Placers (`trunk_placer`)
```json
{ "type": "minecraft:<placer_type>", ... }
```
| Placer | Fields |
|--------|--------|
| `straight_trunk_placer` | `base_height`, `height_rand_a`, `height_rand_b` |
| `forking_trunk_placer` | `base_height`, `height_rand_a`, `height_rand_b` |
| `giant_trunk_placer` | `base_height`, `height_rand_a`, `height_rand_b` |
| `mega_jungle_trunk_placer` | `base_height`, `height_rand_a`, `leaf_placement_height` |
| `dark_oak_trunk_placer` | `base_height`, `height_rand_a`, `height_rand_b` |
| `cherry_trunk_placer` | `base_height`, `height_rand_a`, `branch_length`, `branch_height`, `branch_offset` |
| `upwards_branching_trunk_placer` | `base_height`, `height_rand_a`, `branch_length`, `branch_height` |

---

## Foliage Placers (`foliage_placer`)
```json
{ "type": "minecraft:<placer_type>", ... }
```
| Placer | Fields |
|--------|--------|
| `blob_foliage_placer` | `radius`, `offset`, `height` |
| `spruce_foliage_placer` | `radius`, `offset`, `height` |
| `pine_foliage_placer` | `radius`, `offset`, `height` |
| `jungle_foliage_placer` | `radius`, `offset`, `height` |
| `acacia_foliage_placer` | `radius`, `offset` |
| `dark_oak_foliage_placer` | `radius`, `offset` |
| `mega_pine_foliage_placer` | `radius`, `offset`, `height` |
| `random_spread_foliage_placer` | `radius`, `offset`, `height`, `spread` |
| `cherry_foliage_placer` | `radius`, `offset`, `height`, `leaf_density`, `leaf_radius`, `leaf_height`, `leaf_offset` |

---

## Root Placers (`root_placer`) – optional
```json
{ "type": "minecraft:<root_type>", ... }
```
Typical fields (vary by type):
* `root_block_provider` – a BlockStateProvider.
* `root_placement_attempts` – int.
* `root_height` – int.

---

## Decorators (`decorators` array)
Each entry is a **Decorator** object:
```json
{ "type": "minecraft:<decorator_type>", ... }
```
Common decorators:
| Decorator | Fields |
|----------|--------|
| `beehive` | `probability` (float) |
| `alter_ground` | `block` (BlockStateProvider) |
| `leave_vine` | `probability` (float) |
| `trunk_vine` | `probability` (float) |
| `cocoa` | `probability` (float) |
| `tree_attached_to_ground` | – |
| `tree_fruit` | `probability` (float) |
| `tree_moss` | `probability` (float) |
| `tree_fancy` | – |
| `tree_biome` | – |
| `tree_podzol` | – |
| `tree_falling_leaf` | – |
| `tree_beehive` | `probability` (float) |

*The full list grows with each Minecraft version; consult the wiki for the latest.*

---

## Example 1 – Classic Oak Tree
```json
{
  "type": "minecraft:tree",
  "config": {
    "ignore_vines": false,
    "force_dirt": false,
    "trunk_provider": {
      "type": "minecraft:simple_state_provider",
      "state": { "Name": "minecraft:oak_log", "Properties": { "axis": "y" } }
    },
    "foliage_provider": {
      "type": "minecraft:simple_state_provider",
      "state": { "Name": "minecraft:oak_leaves" }
    },
    "minimum_size": {
      "type": "minecraft:two_layers_feature_size",
      "base_height": 4,
      "first_random_height": 2,
      "second_random_height": 0
    },
    "trunk_placer": {
      "type": "minecraft:straight_trunk_placer",
      "base_height": 4,
      "height_rand_a": 2,
      "height_rand_b": 0
    },
    "foliage_placer": {
      "type": "minecraft:blob_foliage_placer",
      "radius": 2,
      "offset": 0,
      "height": 3
    },
    "decorators": []
  }
}
```
A simple oak with a straight trunk and blob foliage.

---

## Example 2 – Fancy Oak (forking trunk + vines)
```json
{
  "type": "minecraft:tree",
  "config": {
    "ignore_vines": false,
    "trunk_provider": {
      "type": "minecraft:simple_state_provider",
      "state": { "Name": "minecraft:oak_log", "Properties": { "axis": "y" } }
    },
    "foliage_provider": {
      "type": "minecraft:simple_state_provider",
      "state": { "Name": "minecraft:oak_leaves" }
    },
    "minimum_size": {
      "type": "minecraft:two_layers_feature_size",
      "base_height": 5,
      "first_random_height": 2,
      "second_random_height": 1
    },
    "trunk_placer": {
      "type": "minecraft:forking_trunk_placer",
      "base_height": 5,
      "height_rand_a": 2,
      "height_rand_b": 1
    },
    "foliage_placer": {
      "type": "minecraft:blob_foliage_placer",
      "radius": 2,
      "offset": 0,
      "height": 3
    },
    "decorators": [
      { "type": "minecraft:leave_vine", "probability": 0.25 },
      { "type": "minecraft:trunk_vine", "probability": 0.15 },
      { "type": "minecraft:beehive", "probability": 0.02 }
    ]
  }
}
```
This tree uses the **forking trunk placer** (the “fancy oak” shape) and adds vines and a low‑chance beehive.

---

## Usage Tips
* **Version gating** – Some placers or decorators only exist from a certain Minecraft version onward.  Check the `minVersion` field in the wiki when targeting older versions.
* **BlockStateProvider shortcuts** – For most simple cases you can use the `simple_state_provider` with a single `state` object as shown in the examples.
* **Testing** – Place the JSON file under `data/<namespace>/worldgen/configured_feature/` and reference it from a `placed_feature` to see it in‑game.

---

*Document generated on 2025‑11‑25.*
