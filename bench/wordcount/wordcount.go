// word-count: generate a text of pseudo-random words, split it and count the words in a hash map (built-in map[string]int).
// Output: total, distinct, max count, order-independent checksum.
package main

import (
	"fmt"
	"strings"
)

const WORDS = 20000000
const MOD = 1000000007

var seed int64 = 42

func next() int {
	seed = (seed*1103515245 + 12345) & 0x7fffffff
	return int(seed >> 8)
}

func generate() string {
	var sb strings.Builder
	for i := 0; i < WORDS; i++ {
		a := next() % 50000
		b := next() % 50000
		x := int(int64(a) * int64(b) / 50000)
		n := 0
		for {
			sb.WriteByte(byte('a' + x%26))
			x /= 26
			n++
			if !(x > 0 || n < 3) {
				break
			}
		}
		sb.WriteByte(' ')
	}
	return sb.String()
}

func hash(w string) int64 {
	var h int64
	for i := 0; i < len(w); i++ {
		h = (h*31 + int64(w[i])) % MOD
	}
	return h
}

func main() {
	text := generate()
	counts := make(map[string]int)
	pos, total := 0, 0
	for pos < len(text) {
		e := pos + strings.IndexByte(text[pos:], ' ')
		counts[text[pos:e]]++
		total++
		pos = e + 1
	}
	maxCount := 0
	var chk int64
	for w, c := range counts {
		if c > maxCount {
			maxCount = c
		}
		chk = (chk + hash(w)*int64(c)) % MOD
	}
	fmt.Println(total, len(counts), maxCount, chk)
}
