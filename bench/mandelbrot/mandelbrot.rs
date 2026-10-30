// mandelbrot: N x N set, 8 pixels packed per byte, 50 iterations; output = checksum of the bitmap (not the PBM).
const N: i32 = 4000;
const MOD: i64 = 1_000_000_007;

fn main() {
    let mut chk: i64 = 0;
    for y in 0..N {
        let ci = 2.0 * (y as f64) / (N as f64) - 1.0;
        for xb in 0..(N / 8) {
            let mut bits: i64 = 0;
            for bit in 0..8 {
                let x = xb * 8 + bit;
                let cr = 2.0 * (x as f64) / (N as f64) - 1.5;
                let (mut zr, mut zi) = (0.0f64, 0.0f64);
                let mut inside = 1;
                for _ in 0..50 {
                    let tr = zr * zr - zi * zi + cr;
                    zi = 2.0 * zr * zi + ci;
                    zr = tr;
                    if zr * zr + zi * zi > 4.0 {
                        inside = 0;
                        break;
                    }
                }
                bits = (bits << 1) | inside;
            }
            chk = (chk * 131 + bits) % MOD;
        }
    }
    println!("{}", chk);
}
