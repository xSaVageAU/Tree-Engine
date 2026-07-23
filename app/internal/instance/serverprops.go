package instance

import (
	"fmt"
	"os"
)

// WriteServerProperties writes a server.properties tuned for a headless
// instance: no real player ever joins (PhantomWorld fakes everything actual
// tree generation needs), so a normal world's terrain/structures/view
// distance are pure startup-time waste. A flat, structure-less world with a
// minimal view distance keeps "Preparing spawn area" - typically the biggest
// contributor to vanilla boot time - close to instant. server-port is
// randomized (via FindFreePort in setup.go) so this instance never collides
// with a real Minecraft server the user might run on the default 25565.
func WriteServerProperties(l Layout, gamePort int) error {
	contents := fmt.Sprintf(`server-port=%d
level-type=flat
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
