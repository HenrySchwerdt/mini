// string-builder / concat: (A) one big buffer filled with ints, chars and literals, (B) many short strings made by
// concatenation. Output: lengths and checksums.
package main

import (
	"fmt"
	"strconv"
)

const APPENDS = 12000000
const CONCATS = 6000000
const MOD = 1000000007

func main() {
	sb := make([]byte, 0, 1<<16)
	for i := 0; i < APPENDS; i++ {
		sb = strconv.AppendInt(sb, int64(i), 10)
		sb = append(sb, ',')
		if i%5 == 0 {
			sb = append(sb, "item"...)
		}
	}
	var chkA int64
	for idx := 0; idx < len(sb); idx += 7 {
		chkA = (chkA*31 + int64(sb[idx])) % MOD
	}
	var sumB, chkB int64
	for i := 0; i < CONCATS; i++ {
		s := "item-" + strconv.Itoa(i) + ":" + strconv.Itoa(i*7)
		sumB += int64(len(s))
		chkB = (chkB*31 + int64(s[len(s)-1]) + int64(s[5])) % MOD
	}
	fmt.Println(len(sb), chkA, sumB, chkB)
}
