package singtun

import (
	tun "github.com/sagernet/sing-tun"
	"golang.org/x/sys/unix"
)

func openDevice(options tun.Options) (tun.Tun, error) {
	fd, err := unix.FcntlInt(uintptr(options.FileDescriptor), unix.F_DUPFD_CLOEXEC, 3)
	if err != nil {
		return nil, err
	}
	if err = unix.SetNonblock(fd, true); err != nil {
		_ = unix.Close(fd)
		return nil, err
	}
	options.FileDescriptor = fd
	device, err := tun.New(options)
	if err != nil {
		_ = unix.Close(fd)
	}
	return device, err
}
