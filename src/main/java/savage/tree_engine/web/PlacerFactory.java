package savage.tree_engine.web;

import net.minecraft.util.math.intprovider.ConstantIntProvider;
import net.minecraft.util.math.intprovider.UniformIntProvider;
import net.minecraft.world.gen.foliage.*;
import net.minecraft.world.gen.trunk.*;

public class PlacerFactory {
    
    public static TrunkPlacer createTrunkPlacer(String type, int heightMin, int heightMax) {
        int heightRand = Math.max(0, heightMax - heightMin);
        if (type == null) type = "straight";
        
        switch (type.toLowerCase()) {
            case "forking":
                return new ForkingTrunkPlacer(heightMin, heightRand, 0);
            case "giant":
                return new GiantTrunkPlacer(heightMin, heightRand, 0);
            case "mega_jungle":
                return new MegaJungleTrunkPlacer(heightMin, heightRand, 19);
            case "dark_oak":
                return new DarkOakTrunkPlacer(heightMin, heightRand, 0);
            case "cherry":
                return new CherryTrunkPlacer(heightMin, heightRand, 0, 
                    ConstantIntProvider.create(2), 
                    ConstantIntProvider.create(3),
                    UniformIntProvider.create(-1, -2), 
                    ConstantIntProvider.create(-1));
            case "upwards_branching":
            case "straight":
            default:
                return new StraightTrunkPlacer(heightMin, heightRand, 0);
        }
    }
    
    public static FoliagePlacer createFoliagePlacer(String type, int radius, int offset, int height) {
        if (type == null) type = "blob";
        
        switch (type.toLowerCase()) {
            case "spruce":
                return new SpruceFoliagePlacer(
                    ConstantIntProvider.create(radius), 
                    ConstantIntProvider.create(offset), 
                    ConstantIntProvider.create(height));
            case "pine":
                return new PineFoliagePlacer(
                    ConstantIntProvider.create(radius), 
                    ConstantIntProvider.create(offset), 
                    ConstantIntProvider.create(height));
            case "jungle":
                return new JungleFoliagePlacer(
                    ConstantIntProvider.create(radius), 
                    ConstantIntProvider.create(offset), 
                    height);
            case "acacia":
                return new AcaciaFoliagePlacer(
                    ConstantIntProvider.create(radius), 
                    ConstantIntProvider.create(offset));
            case "dark_oak":
                return new DarkOakFoliagePlacer(
                    ConstantIntProvider.create(radius), 
                    ConstantIntProvider.create(offset));
            case "mega_pine":
                return new MegaPineFoliagePlacer(
                    ConstantIntProvider.create(radius), 
                    ConstantIntProvider.create(offset), 
                    ConstantIntProvider.create(height));
            case "random_spread":
                return new RandomSpreadFoliagePlacer(
                    ConstantIntProvider.create(radius), 
                    ConstantIntProvider.create(offset), 
                    ConstantIntProvider.create(height), 
                    50);
            case "cherry":
                return new CherryFoliagePlacer(
                    ConstantIntProvider.create(radius), 
                    ConstantIntProvider.create(offset), 
                    ConstantIntProvider.create(height), 
                    0.25f, 0.5f, 0.16666667f, 0.33333334f);
            case "blob":
            default:
                return new BlobFoliagePlacer(
                    ConstantIntProvider.create(radius), 
                    ConstantIntProvider.create(offset), 
                    height);
        }
    }
}
