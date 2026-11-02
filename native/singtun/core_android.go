package singtun

// Keep the pinned integrated core in this module's dependency graph. gomobile binds
// this package and libv2ray together, producing one libgojni and one Go runtime.
import _ "github.com/2dust/AndroidLibXrayLite"
