// mandelbrot: N x N set, 8 pixels packed per byte, 50 iterations; output = checksum of the bitmap (not the PBM).
package main

import "fmt"

const N = 4000
const MOD = 1000000007

func main() {
	var chk int64
	for y := 0; y < N; y++ {
		ci := 2.0*float64(y)/float64(N) - 1.0
		for xb := 0; xb < N/8; xb++ {
			var bits int64
			for bit := 0; bit < 8; bit++ {
				x := xb*8 + bit
				cr := 2.0*float64(x)/float64(N) - 1.5
				zr, zi := 0.0, 0.0
				var inside int64 = 1
				for it := 0; it < 50; it++ {
					tr := zr*zr - zi*zi + cr
					zi = 2.0*zr*zi + ci
					zr = tr
					if zr*zr+zi*zi > 4.0 {
						inside = 0
						break
					}
				}
				bits = (bits << 1) | inside
			}
			chk = (chk*131 + bits) % MOD
		}
	}
	fmt.Println(chk)
}
