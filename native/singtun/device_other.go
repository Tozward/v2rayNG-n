//go:build !linux && !android

package singtun

import (
	"errors"
	tun "github.com/sagernet/sing-tun"
)

func openDevice(tun.Options) (tun.Tun, error) {
	return nil, errors.New("Sing TUN fd adapter requires Android/Linux")
}
