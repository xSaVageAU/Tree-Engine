package savage.tree_engine.web;

import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Property;

public class BlockInfo {
    public int x;
    public int y;
    public int z;
    public BlockState blockState;

    public BlockInfo(int x, int y, int z, BlockState blockState) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.blockState = blockState;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("x", x);
        json.addProperty("y", y);
        json.addProperty("z", z);

        // Serialize block state
        JsonObject stateJson = new JsonObject();
        stateJson.addProperty("Name", blockState.getBlock().getRegistryEntry().getKey().get().getValue().toString());

        // Add properties if any
        JsonObject properties = new JsonObject();
        for (Property<?> property : blockState.getProperties()) {
            properties.addProperty(property.getName(), blockState.get(property).toString());
        }
        if (properties.size() > 0) {
            stateJson.add("Properties", properties);
        }

        json.add("blockState", stateJson);
        return json;
    }
}
