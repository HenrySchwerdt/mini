// spectral-norm: dense double loops. Same algorithm in every language; output = the norm * 1e9, rounded.
package main

import (
	"fmt"
	"math"
)

const N = 5000

func a(i, j int) float64 { return 1.0 / float64((i+j)*(i+j+1)/2+i+1) }

func mulAv(v, out []float64) {
	for i := 0; i < N; i++ {
		s := 0.0
		for j := 0; j < N; j++ {
			s += a(i, j) * v[j]
		}
		out[i] = s
	}
}

func mulAtv(v, out []float64) {
	for i := 0; i < N; i++ {
		s := 0.0
		for j := 0; j < N; j++ {
			s += a(j, i) * v[j]
		}
		out[i] = s
	}
}

func mulAtAv(v, out, tmp []float64) {
	mulAv(v, tmp)
	mulAtv(tmp, out)
}

func main() {
	u := make([]float64, N)
	v := make([]float64, N)
	tmp := make([]float64, N)
	for i := range u {
		u[i] = 1.0
	}
	for i := 0; i < 10; i++ {
		mulAtAv(u, v, tmp)
		mulAtAv(v, u, tmp)
	}
	vbv, vv := 0.0, 0.0
	for i := 0; i < N; i++ {
		vbv += u[i] * v[i]
		vv += v[i] * v[i]
	}
	fmt.Println(int64(math.Sqrt(vbv/vv)*1e9 + 0.5))
}
