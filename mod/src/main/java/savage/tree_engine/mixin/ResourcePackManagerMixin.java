package savage.tree_engine.mixin;

import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import savage.tree_engine.registry.TreeEngineResourcePackProvider;

import java.util.HashSet;
import java.util.Set;

@Mixin(ResourcePackManager.class)
public class ResourcePackManagerMixin {
    @Shadow @Mutable
    private Set<ResourcePackProvider> providers;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(ResourcePackProvider[] providers, CallbackInfo ci) {
        // Create a mutable set if it's not already (it usually is)
        if (!(this.providers instanceof HashSet)) {
            this.providers = new HashSet<>(this.providers);
        }
        
        // Add our custom provider
        savage.tree_engine.TreeEngine.LOGGER.info("ResourcePackManagerMixin: Injecting TreeEngineResourcePackProvider");
        this.providers.add(new TreeEngineResourcePackProvider());
    }
}
