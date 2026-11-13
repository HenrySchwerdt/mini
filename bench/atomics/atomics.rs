// atomics: 4 threads add to one shared counter 5000000 times each and run a compare-and-set loop 1000000 times each.
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::thread;

const THREADS: usize = 4;
const ADDS: usize = 5000000;
const CAS: usize = 1000000;

fn main() {
    let a = Arc::new(AtomicU64::new(0));
    let b = Arc::new(AtomicU64::new(0));
    let mut ts = Vec::new();
    for _ in 0..THREADS {
        let a = Arc::clone(&a);
        let b = Arc::clone(&b);
        ts.push(thread::spawn(move || {
            for _ in 0..ADDS {
                a.fetch_add(1, Ordering::SeqCst);
            }
            for _ in 0..CAS {
                loop {
                    let cur = b.load(Ordering::SeqCst);
                    if b.compare_exchange(cur, cur + 3, Ordering::SeqCst, Ordering::SeqCst).is_ok() {
                        break;
                    }
                }
            }
        }));
    }
    for t in ts {
        t.join().unwrap();
    }
    println!("{}", a.load(Ordering::SeqCst));
    println!("{}", b.load(Ordering::SeqCst));
}
