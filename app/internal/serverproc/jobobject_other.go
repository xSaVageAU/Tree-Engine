//go:build !windows

package serverproc

import "os"

// jobObject is a no-op on non-Windows platforms. v1 of this launcher only
// ships for Windows (see plan's "Explicitly Out of Scope"); this stub exists
// only so the package still compiles elsewhere during development.
type jobObject struct{}

func newJobObject() (*jobObject, error) {
	return &jobObject{}, nil
}

func (j *jobObject) assign(_ *os.Process) error {
	return nil
}

func (j *jobObject) Close() error {
	return nil
}
