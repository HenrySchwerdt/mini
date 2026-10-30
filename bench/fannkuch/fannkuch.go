// fannkuch-redux: integer array permutations and flips. Same algorithm in every language.
package main

import "fmt"

const N = 11

func main() {
	var perm, perm1, count [N]int
	maxFlips, permCount, checksum := 0, 0, 0
	for i := 0; i < N; i++ {
		perm1[i] = i
	}
	r := N
	for {
		for r != 1 {
			count[r-1] = r
			r--
		}
		perm = perm1
		flips := 0
		for {
			k := perm[0]
			if k == 0 {
				break
			}
			k2 := (k + 1) >> 1
			for i := 0; i < k2; i++ {
				perm[i], perm[k-i] = perm[k-i], perm[i]
			}
			flips++
		}
		if flips > maxFlips {
			maxFlips = flips
		}
		if permCount%2 == 0 {
			checksum += flips
		} else {
			checksum -= flips
		}
		for {
			if r == N {
				fmt.Printf("%d\nPfannkuchen(%d) = %d\n", checksum, N, maxFlips)
				return
			}
			p0 := perm1[0]
			for i := 0; i < r; i++ {
				perm1[i] = perm1[i+1]
			}
			perm1[r] = p0
			count[r]--
			if count[r] > 0 {
				break
			}
			r++
		}
		permCount++
	}
}
