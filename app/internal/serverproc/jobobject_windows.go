//go:build windows

package serverproc

import (
	"os"
	"unsafe"

	"golang.org/x/sys/windows"
)

// jobObject ties a child process's lifetime to this launcher process: if the
// launcher exits for any reason - including being force-killed via Task
// Manager, crashing, or an OS-triggered shutdown - Windows automatically
// terminates every process assigned to the job too.
//
// Without this, a forcibly-killed launcher orphans the Minecraft server
// process: it keeps running invisibly, still holding the port and the
// world's session.lock, so the next "Start Server" click fails outright
// (observed directly during testing).
type jobObject struct {
	handle windows.Handle
}

func newJobObject() (*jobObject, error) {
	handle, err := windows.CreateJobObject(nil, nil)
	if err != nil {
		return nil, err
	}

	info := windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION{
		BasicLimitInformation: windows.JOBOBJECT_BASIC_LIMIT_INFORMATION{
			LimitFlags: windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
		},
	}
	if _, err := windows.SetInformationJobObject(
		handle,
		windows.JobObjectExtendedLimitInformation,
		uintptr(unsafe.Pointer(&info)),
		uint32(unsafe.Sizeof(info)),
	); err != nil {
		windows.CloseHandle(handle)
		return nil, err
	}

	return &jobObject{handle: handle}, nil
}

// assign puts process under this job, so its lifetime becomes tied to ours.
func (j *jobObject) assign(process *os.Process) error {
	handle, err := windows.OpenProcess(windows.PROCESS_ALL_ACCESS, false, uint32(process.Pid))
	if err != nil {
		return err
	}
	defer windows.CloseHandle(handle)
	return windows.AssignProcessToJobObject(j.handle, handle)
}

func (j *jobObject) Close() error {
	return windows.CloseHandle(j.handle)
}
