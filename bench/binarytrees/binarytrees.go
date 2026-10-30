// binary-trees: allocation heavy (GC). Same algorithm in every language.
package main

import "fmt"

const maxDepth = 18

type Node struct{ left, right *Node }

func bottomUp(d int) *Node {
	if d == 0 {
		return &Node{}
	}
	return &Node{bottomUp(d - 1), bottomUp(d - 1)}
}

func check(n *Node) int {
	if n.left == nil {
		return 1
	}
	return 1 + check(n.left) + check(n.right)
}

func main() {
	minDepth := 4
	stretch := maxDepth + 1
	fmt.Printf("stretch tree of depth %d\t check: %d\n", stretch, check(bottomUp(stretch)))
	longLived := bottomUp(maxDepth)
	for d := minDepth; d <= maxDepth; d += 2 {
		iters := 1 << (maxDepth - d + minDepth)
		chk := 0
		for i := 0; i < iters; i++ {
			chk += check(bottomUp(d))
		}
		fmt.Printf("%d\t trees of depth %d\t check: %d\n", iters, d, chk)
	}
	fmt.Printf("long lived tree of depth %d\t check: %d\n", maxDepth, check(longLived))
}
