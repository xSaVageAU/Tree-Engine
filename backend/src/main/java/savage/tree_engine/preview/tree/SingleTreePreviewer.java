package savage.tree_engine.preview.tree;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import savage.tree_engine.api.ApiException;
import savage.tree_engine.preview.BlockDto;

import java.util.List;

/**
 * Generates one tree, in isolation, on a fabricated ground plane.
 *
 * This is the default preview mode: it answers "what does this one feature
 * produce", with no terrain, no neighbours and no placement rules. It is
 * deliberately not a simulation of how the tree appears in a world - that is
 * what the natural chunk preview is for.
 */
public final class SingleTreePreviewer {
	private final MinecraftServer server;

	public SingleTreePreviewer(MinecraftServer server) {
		this.server = server;
	}

	/**
	 * The server's own registries, for previews that need no custom datapack
	 * (an inline feature referencing only vanilla blocks).
	 */
	public RegistryAccess serverRegistries() {
		return server.registryAccess();
	}

	public Result preview(
		RegistryAccess registries, JsonElement featureJson, String featureId,
		String biomeId, long seed, boolean includeGround) {

		ConfiguredFeature<?, ?> feature = featureJson != null
			? parse(registries, featureJson)
			: lookup(registries, featureId);

		GroundPlane ground = GroundPlane.create(registries, biomeId);
		RandomSource random = RandomSource.create(seed);
		CaptureLevel level = new CaptureLevel(server, registries, ground, random);
		FlatGenerator generator = new FlatGenerator(new FixedBiomeSource(ground.biome()));

		boolean placed;
		try {
			placed = feature.place(level, generator, random, BlockPos.ZERO);
		} catch (Exception e) {
			throw ApiException.internal("Feature threw while generating", e);
		}

		return new Result(level.captured(includeGround), placed);
	}

	/**
	 * Parses a feature straight from the request body. DIRECT_CODEC is the
	 * raw-JSON codec; CODEC would expect a registry reference instead of an
	 * inline definition.
	 */
	private static ConfiguredFeature<?, ?> parse(RegistryAccess registries, JsonElement json) {
		RegistryOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
		DataResult<ConfiguredFeature<?, ?>> result =
			ConfiguredFeature.DIRECT_CODEC.parse(ops, json);
		return result.getOrThrow(error ->
			ApiException.badRequest("Invalid feature configuration", error));
	}

	/** Resolves a feature already present in the session's registries. */
	private static ConfiguredFeature<?, ?> lookup(RegistryAccess registries, String featureId) {
		if (featureId == null || featureId.isBlank()) {
			throw ApiException.badRequest("Provide either 'feature' or 'featureId'");
		}
		Identifier id = Identifier.tryParse(featureId);
		if (id == null) {
			throw ApiException.badRequest("Not a valid feature id: " + featureId);
		}
		ConfiguredFeature<?, ?> feature = registries
			.lookupOrThrow(Registries.CONFIGURED_FEATURE)
			.getValue(ResourceKey.create(Registries.CONFIGURED_FEATURE, id));
		if (feature == null) {
			throw ApiException.notFound("No such configured feature: " + featureId);
		}
		return feature;
	}

	/**
	 * @param blocks  everything the feature placed
	 * @param placed  the feature's own verdict; false means it declined to
	 *                generate (bad soil, not enough room), which is a real
	 *                result worth showing rather than an error
	 */
	public record Result(List<BlockDto> blocks, boolean placed) {
	}
}
