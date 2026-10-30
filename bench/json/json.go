// json-like: generate a JSON document from a seeded generator, parse it into a tree (recursive descent, strings with escapes,
// objects as key/value lists), walk the tree. Output: text length, node count, checksum of the walk.
package main

import (
	"fmt"
	"strconv"
)

const TOP = 60000
const REPS = 5
const MOD = 1000000007

var seed int64 = 7

func next() int {
	seed = (seed*1103515245 + 12345) & 0x7fffffff
	return int(seed >> 8)
}

func leaf(sb []byte) []byte {
	switch next() % 6 {
	case 0:
		sb = append(sb, "null"...)
	case 1:
		sb = append(sb, "true"...)
	case 2:
		sb = append(sb, "false"...)
	case 3:
		sb = strconv.AppendInt(sb, int64(next()%200000-100000), 10)
	case 4:
		sb = append(sb, "\"s"...)
		sb = strconv.AppendInt(sb, int64(next()%5000), 10)
		sb = append(sb, '"')
	default:
		sb = append(sb, "\"a\\nb\\\"c"...)
		sb = strconv.AppendInt(sb, int64(next()%5000), 10)
		sb = append(sb, "\\\\\""...)
	}
	return sb
}

func gen(sb []byte, depth int) []byte {
	k := next() % 12
	if depth >= 6 || k < 5 {
		return leaf(sb)
	} else if k < 8 {
		n := 1 + next()%4
		sb = append(sb, '{')
		for i := 0; i < n; i++ {
			if i > 0 {
				sb = append(sb, ", "...)
			}
			sb = append(sb, "\"k"...)
			sb = strconv.AppendInt(sb, int64(next()%1000), 10)
			sb = append(sb, "\": "...)
			sb = gen(sb, depth+1)
		}
		sb = append(sb, '}')
	} else {
		n := next() % 5
		sb = append(sb, '[')
		for i := 0; i < n; i++ {
			if i > 0 {
				sb = append(sb, ", "...)
			}
			sb = gen(sb, depth+1)
		}
		sb = append(sb, ']')
	}
	return sb
}

const (
	jNull = iota
	jBool
	jNum
	jStr
	jArr
	jObj
)

type J struct {
	kind  int
	num   int64
	str   string
	items []*J
	keys  []string
}

type Parser struct {
	s   string
	pos int
}

func (p *Parser) skipWs() {
	for p.pos < len(p.s) && p.s[p.pos] == ' ' {
		p.pos++
	}
}

func (p *Parser) parseString() string {
	p.pos++
	start := p.pos
	esc := false
	for p.s[p.pos] != '"' {
		if p.s[p.pos] == '\\' {
			esc = true
			p.pos++
		}
		p.pos++
	}
	raw := p.s[start:p.pos]
	p.pos++
	if !esc {
		return raw
	}
	out := make([]byte, 0, len(raw))
	for i := 0; i < len(raw); i++ {
		c := raw[i]
		if c == '\\' {
			i++
			d := raw[i]
			if d == 'n' {
				out = append(out, '\n')
			} else {
				out = append(out, d)
			}
		} else {
			out = append(out, c)
		}
	}
	return string(out)
}

func (p *Parser) parseValue() *J {
	p.skipWs()
	c := p.s[p.pos]
	if c == '{' {
		p.pos++
		j := &J{kind: jObj}
		p.skipWs()
		if p.s[p.pos] == '}' {
			p.pos++
			return j
		}
		for {
			p.skipWs()
			k := p.parseString()
			p.skipWs()
			p.pos++
			v := p.parseValue()
			j.keys = append(j.keys, k)
			j.items = append(j.items, v)
			p.skipWs()
			d := p.s[p.pos]
			p.pos++
			if d == '}' {
				break
			}
		}
		return j
	}
	if c == '[' {
		p.pos++
		j := &J{kind: jArr}
		p.skipWs()
		if p.s[p.pos] == ']' {
			p.pos++
			return j
		}
		for {
			j.items = append(j.items, p.parseValue())
			p.skipWs()
			d := p.s[p.pos]
			p.pos++
			if d == ']' {
				break
			}
		}
		return j
	}
	if c == '"' {
		return &J{kind: jStr, str: p.parseString()}
	}
	if c == 't' {
		p.pos += 4
		return &J{kind: jBool, num: 1}
	}
	if c == 'f' {
		p.pos += 5
		return &J{kind: jBool, num: 0}
	}
	if c == 'n' {
		p.pos += 4
		return &J{kind: jNull}
	}
	neg := false
	if c == '-' {
		neg = true
		p.pos++
	}
	var v int64
	for p.pos < len(p.s) && p.s[p.pos] >= '0' && p.s[p.pos] <= '9' {
		v = v*10 + int64(p.s[p.pos]-'0')
		p.pos++
	}
	if neg {
		v = -v
	}
	return &J{kind: jNum, num: v}
}

var gh, gn int64

func step(x int64) {
	gh = (gh*31 + x) % MOD
	gn++
}

func walk(j *J) {
	switch j.kind {
	case jNull:
		step(1)
	case jBool:
		if j.num != 0 {
			step(2)
		} else {
			step(3)
		}
	case jNum:
		step(j.num + 100000)
	case jStr:
		t := int64(len(j.str))
		for i := 0; i < len(j.str); i++ {
			t += int64(j.str[i])
		}
		step(t)
	case jArr:
		step(13 + int64(len(j.items)))
		for _, it := range j.items {
			walk(it)
		}
	case jObj:
		step(11 + int64(len(j.keys)))
		for i, k := range j.keys {
			step(int64(len(k)))
			walk(j.items[i])
		}
	}
}

func main() {
	sb := make([]byte, 0, 1<<20)
	sb = append(sb, '[')
	for i := 0; i < TOP; i++ {
		if i > 0 {
			sb = append(sb, ", "...)
		}
		sb = gen(sb, 1)
	}
	sb = append(sb, ']')
	text := string(sb)
	var n, h int64
	for r := 0; r < REPS; r++ {
		p := &Parser{s: text}
		tree := p.parseValue()
		gh, gn = 0, 0
		walk(tree)
		n, h = gn, gh
	}
	fmt.Println(len(text), n, h)
}
