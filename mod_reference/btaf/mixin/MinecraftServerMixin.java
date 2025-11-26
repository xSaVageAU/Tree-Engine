package savage.btaf.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import savage.btaf.worldgen.SavsWorldGen;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {
    @Inject(method = "loadWorld", at = @At("RETURN"))
    private void onLoadWorld(CallbackInfo ci) {
        SavsWorldGen.onServerStarted((MinecraftServer)(Object)this);
    }
}
