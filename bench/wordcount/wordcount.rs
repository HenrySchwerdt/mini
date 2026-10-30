// word-count: generate a text of pseudo-random words, split it and count the words in a hash map (std HashMap<&str, u32>).
// Output: total, distinct, max count, order-independent checksum.
use std::collections::HashMap;

const WORDS: usize = 20_000_000;
const MOD: i64 = 1_000_000_007;

struct Rng(i64);

impl Rng {
    fn next(&mut self) -> i32 {
        self.0 = (self.0 * 1103515245 + 12345) & 0x7fffffff;
        (self.0 >> 8) as i32
    }
}

fn generate() -> String {
    let mut rng = Rng(42);
    let mut s = String::new();
    for _ in 0..WORDS {
        let a = rng.next() % 50000;
        let b = rng.next() % 50000;
        let mut x = (a as i64 * b as i64 / 50000) as i32;
        let mut n = 0;
        loop {
            s.push((b'a' + (x % 26) as u8) as char);
            x /= 26;
            n += 1;
            if !(x > 0 || n < 3) {
                break;
            }
        }
        s.push(' ');
    }
    s
}

fn hash(w: &str) -> i64 {
    let mut h = 0i64;
    for c in w.bytes() {
        h = (h * 31 + c as i64) % MOD;
    }
    h
}

fn main() {
    let text = generate();
    let mut counts: HashMap<&str, i32> = HashMap::new();
    let mut pos = 0;
    let mut total = 0;
    while pos < text.len() {
        let e = pos + text[pos..].find(' ').unwrap();
        *counts.entry(&text[pos..e]).or_insert(0) += 1;
        total += 1;
        pos = e + 1;
    }
    let mut max_count = 0;
    let mut chk = 0i64;
    for (w, c) in &counts {
        if *c > max_count {
            max_count = *c;
        }
        chk = (chk + hash(w) * (*c as i64)) % MOD;
    }
    println!("{} {} {} {}", total, counts.len(), max_count, chk);
}
