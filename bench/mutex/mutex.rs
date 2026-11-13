// mutex: 4 threads take one lock 1000000 times each and increment a shared counter (std::sync::Mutex).
use std::sync::{Arc, Mutex};
use std::thread;

const THREADS: usize = 4;
const ROUNDS: usize = 1000000;

fn main() {
    let c = Arc::new(Mutex::new(0u64));
    let mut ts = Vec::new();
    for _ in 0..THREADS {
        let c = Arc::clone(&c);
        ts.push(thread::spawn(move || {
            for _ in 0..ROUNDS {
                *c.lock().unwrap() += 1;
            }
        }));
    }
    for t in ts {
        t.join().unwrap();
    }
    println!("{}", *c.lock().unwrap());
}
