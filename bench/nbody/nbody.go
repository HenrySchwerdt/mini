// n-body: floating point math on 5 bodies. Same algorithm in every language; output = energies * 1e9 truncated to integers.
package main

import (
	"fmt"
	"math"
)

const steps = 25000000
const pi = 3.141592653589793
const solarMass = 4.0 * pi * pi
const daysPerYear = 365.24

type Body struct{ x, y, z, vx, vy, vz, mass float64 }

var bodies = [5]Body{
	{0, 0, 0, 0, 0, 0, solarMass},
	{4.84143144246472090e+00, -1.16032004402742839e+00, -1.03622044471123109e-01, 1.66007664274403694e-03 * daysPerYear,
		7.69901118419740425e-03 * daysPerYear, -6.90460016972063023e-05 * daysPerYear, 9.54791938424326609e-04 * solarMass},
	{8.34336671824457987e+00, 4.12479856412430479e+00, -4.03523417114321381e-01, -2.76742510726862411e-03 * daysPerYear,
		4.99852801234917238e-03 * daysPerYear, 2.30417297573763929e-05 * daysPerYear, 2.85885980666130812e-04 * solarMass},
	{1.28943695621391310e+01, -1.51111514016986312e+01, -2.23307578892655734e-01, 2.96460137564761618e-03 * daysPerYear,
		2.37847173959480950e-03 * daysPerYear, -2.96589568540237556e-05 * daysPerYear, 4.36624404335156298e-05 * solarMass},
	{1.53796971148509165e+01, -2.59193146099879641e+01, 1.79258772950371181e-01, 2.68067772490389322e-03 * daysPerYear,
		1.62824170038242295e-03 * daysPerYear, -9.51592254519715870e-05 * daysPerYear, 5.15138902046611451e-05 * solarMass},
}

func offsetMomentum() {
	var px, py, pz float64
	for i := 0; i < 5; i++ {
		px += bodies[i].vx * bodies[i].mass
		py += bodies[i].vy * bodies[i].mass
		pz += bodies[i].vz * bodies[i].mass
	}
	bodies[0].vx = -px / solarMass
	bodies[0].vy = -py / solarMass
	bodies[0].vz = -pz / solarMass
}

func advance(dt float64) {
	for i := 0; i < 5; i++ {
		a := &bodies[i]
		for j := i + 1; j < 5; j++ {
			b := &bodies[j]
			dx, dy, dz := a.x-b.x, a.y-b.y, a.z-b.z
			d2 := dx*dx + dy*dy + dz*dz
			mag := dt / (d2 * math.Sqrt(d2))
			a.vx -= dx * b.mass * mag
			a.vy -= dy * b.mass * mag
			a.vz -= dz * b.mass * mag
			b.vx += dx * a.mass * mag
			b.vy += dy * a.mass * mag
			b.vz += dz * a.mass * mag
		}
	}
	for i := 0; i < 5; i++ {
		b := &bodies[i]
		b.x += dt * b.vx
		b.y += dt * b.vy
		b.z += dt * b.vz
	}
}

func energy() float64 {
	e := 0.0
	for i := 0; i < 5; i++ {
		a := &bodies[i]
		e += 0.5 * a.mass * (a.vx*a.vx + a.vy*a.vy + a.vz*a.vz)
		for j := i + 1; j < 5; j++ {
			b := &bodies[j]
			dx, dy, dz := a.x-b.x, a.y-b.y, a.z-b.z
			e -= (a.mass * b.mass) / math.Sqrt(dx*dx+dy*dy+dz*dz)
		}
	}
	return e
}

func main() {
	offsetMomentum()
	fmt.Println(int64(energy() * 1e9))
	for i := 0; i < steps; i++ {
		advance(0.01)
	}
	fmt.Println(int64(energy() * 1e9))
}
