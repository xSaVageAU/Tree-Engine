package savage.btaf.util;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import savage.btaf.SavsBetterTreesAndFlora;

public class BlockParser {
    /**
     * Parses a block ID string (e.g., "minecraft:dark_oak_log") into a Block instance.
     * Returns the fallback block if parsing fails.
     */
    public static Block parseBlock(String blockId, Block fallback) {
        try {
            Identifier id = Identifier.tryParse(blockId);
            if (id == null) {
                SavsBetterTreesAndFlora.LOGGER.warn("Invalid block ID format: {}, using fallback", blockId);
                return fallback;
            }
            
            Block block = Registries.BLOCK.get(id);
            if (block == Blocks.AIR) {
                SavsBetterTreesAndFlora.LOGGER.warn("Block not found: {}, using fallback", blockId);
                return fallback;
            }
            
            return block;
        } catch (Exception e) {
            SavsBetterTreesAndFlora.LOGGER.error("Error parsing block ID: {}", blockId, e);
            return fallback;
        }
    }
}
