// Package serverproc manages the lifecycle of the spawned Minecraft server
// process: launching it, streaming its console output, detecting readiness,
// and stopping it gracefully via the standard "stop" console command.
package serverproc

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"os/exec"
	"strings"
	"sync"
	"time"
)

const (
	// DefaultHeapMB is the JVM max heap size used for the managed server.
	// Not user-configurable in v1 - see plan's "Explicitly Out of Scope".
	DefaultHeapMB = 2048

	doneMarker = "]: Done ("
	// Logged by ApiServer.listen() once the backend has bound its port.
	// This is a fast path only - callers must not depend on it alone, since
	// a log-format change would otherwise hang startup forever. See
	// app.go's readiness handling, which also polls the port after
	// doneMarker.
	backendReadyMarker = "Tree Engine backend listening on"
)

// Process wraps a running (or stopped) managed server.
type Process struct {
	cmd            *exec.Cmd
	stdin          io.WriteCloser
	job            *jobObject
	onLine         func(line string)
	onDone         func()
	onBackendReady func()

	mu       sync.Mutex
	exited   bool
	exitErr  error
	doneCh   chan struct{}
	doneOnce sync.Once
}

// Options configures a Launch call.
type Options struct {
	JavaPath       string
	JarPath        string
	WorkDir        string
	HeapMB         int
	OnLine         func(line string) // called for every stdout/stderr line
	OnDone         func()            // called once when "Done (...)!' is seen
	OnBackendReady func()            // called once the backend logs that it is listening
}

// Launch spawns `java -Xmx<HeapMB>M -jar <JarPath> nogui` with the given
// working directory and starts streaming its combined stdout/stderr.
func Launch(ctx context.Context, opts Options) (*Process, error) {
	heap := opts.HeapMB
	if heap <= 0 {
		heap = DefaultHeapMB
	}

	cmd := exec.CommandContext(ctx, opts.JavaPath,
		fmt.Sprintf("-Xmx%dM", heap),
		"-jar", opts.JarPath,
		"nogui",
	)
	cmd.Dir = opts.WorkDir

	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return nil, err
	}
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}

	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("failed to start server process: %w", err)
	}

	// Tie the child's lifetime to ours so a force-killed launcher can't leave
	// it running as an orphan (see jobobject_windows.go). Best-effort: if job
	// object creation fails for some reason, still let the server run rather
	// than blocking startup on it.
	job, jobErr := newJobObject()
	if jobErr == nil {
		if err := job.assign(cmd.Process); err != nil {
			job.Close()
			job = nil
		}
	} else {
		job = nil
	}

	p := &Process{
		cmd:            cmd,
		stdin:          stdin,
		job:            job,
		onLine:         opts.OnLine,
		onDone:         opts.OnDone,
		onBackendReady: opts.OnBackendReady,
		doneCh:         make(chan struct{}),
	}

	var wg sync.WaitGroup
	wg.Add(2)
	go p.streamLines(stdout, &wg)
	go p.streamLines(stderr, &wg)

	go func() {
		wg.Wait()
		err := cmd.Wait()
		p.mu.Lock()
		p.exited = true
		p.exitErr = err
		p.mu.Unlock()
		if p.job != nil {
			p.job.Close()
		}
		p.doneOnce.Do(func() { close(p.doneCh) })
	}()

	return p, nil
}

func (p *Process) streamLines(r io.Reader, wg *sync.WaitGroup) {
	defer wg.Done()
	scanner := bufio.NewScanner(r)
	scanner.Buffer(make([]byte, 64*1024), 1024*1024)
	for scanner.Scan() {
		line := scanner.Text()
		if p.onLine != nil {
			p.onLine(line)
		}
		if strings.Contains(line, doneMarker) && p.onDone != nil {
			p.onDone()
		}
		if strings.Contains(line, backendReadyMarker) && p.onBackendReady != nil {
			p.onBackendReady()
		}
	}
	if err := scanner.Err(); err != nil && p.onLine != nil {
		p.onLine(fmt.Sprintf("[launcher] log stream error: %v", err))
	}
}

// Stop asks the server to shut down gracefully via the console "stop"
// command and waits up to timeout before forcibly killing the process.
func (p *Process) Stop(timeout time.Duration) error {
	p.mu.Lock()
	exited := p.exited
	p.mu.Unlock()
	if exited {
		return nil
	}

	if _, err := io.WriteString(p.stdin, "stop\n"); err != nil {
		// Stdin may already be closed if the process is on its way out; fall
		// through to the wait/kill logic rather than treating this as fatal.
		_ = err
	}

	select {
	case <-p.doneCh:
		return nil
	case <-time.After(timeout):
		if p.cmd.Process != nil {
			_ = p.cmd.Process.Kill()
		}
		<-p.doneCh
		return nil
	}
}

// Wait blocks until the process has exited and returns its exit error, if any.
func (p *Process) Wait() error {
	<-p.doneCh
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.exitErr
}

// Running reports whether the process is still alive.
func (p *Process) Running() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return !p.exited
}
