package instance

import (
	"fmt"
	"os"
)

// WriteServerProperties writes a server.properties for a headless instance
// nobody ever joins.
//
// The world must be a normal one. Natural chunk previews decorate terrain
// taken straight from this server (see TerrainSnapshot.java), so the ground
// shape and biome layout the user sees are this world's. An earlier version
// of this file set level-type=flat to skip terrain generation, which was
// correct when every preview stood on a fabricated plane - with chunk
// previews it would silently render a superflat world instead of real
// terrain, which looks plausible and is wrong.
//
// Structures stay off: they are orthogonal to vegetation and are a
// meaningful share of generation cost. That does mean a chunk containing a
// village previews without it, which is the intended trade.
//
// Everything else is trimmed for boot time - no players, minimal view
// distance - and server-port is randomized (FindFreePort in setup.go) so this
// instance never collides with a real Minecraft server on 25565.
func WriteServerProperties(l Layout, gamePort int) error {
	contents := fmt.Sprintf(`server-port=%d
generate-structures=false
spawn-protection=0
view-distance=3
simulation-distance=3
max-players=0
online-mode=false
enable-status=false
allow-nether=false
motd=Tree Engine (headless - do not join)
`, gamePort)
	return os.WriteFile(l.ServerPropertiesPath(), []byte(contents), 0o644)
}
