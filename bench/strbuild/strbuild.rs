// string-builder / concat: (A) one big String filled with ints, chars and literals, (B) many short strings made by
// formatting. Output: lengths and checksums.
use std::fmt::Write;

const APPENDS: i32 = 12_000_000;
const CONCATS: i32 = 6_000_000;
const MOD: i64 = 1_000_000_007;

fn main() {
    let mut sb = String::new();
    for i in 0..APPENDS {
        write!(sb, "{}", i).unwrap();
        sb.push(',');
        if i % 5 == 0 {
            sb.push_str("item");
        }
    }
    let bytes = sb.as_bytes();
    let mut chk_a = 0i64;
    let mut idx = 0;
    while idx < bytes.len() {
        chk_a = (chk_a * 31 + bytes[idx] as i64) % MOD;
        idx += 7;
    }
    let mut sum_b = 0i64;
    let mut chk_b = 0i64;
    for i in 0..CONCATS {
        let s = format!("item-{}:{}", i, i * 7);
        let b = s.as_bytes();
        sum_b += b.len() as i64;
        chk_b = (chk_b * 31 + b[b.len() - 1] as i64 + b[5] as i64) % MOD;
    }
    println!("{} {} {} {}", sb.len(), chk_a, sum_b, chk_b);
}
