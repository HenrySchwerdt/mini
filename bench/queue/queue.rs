// queue: 4 producers push 250000 numbers each through a bounded channel (capacity 1024) to one consumer that sums them.
use std::sync::mpsc::sync_channel;
use std::thread;

const PRODUCERS: usize = 4;
const ITEMS: u64 = 250000;

fn main() {
    let (tx, rx) = sync_channel::<u64>(1024);
    let mut ts = Vec::new();
    for _ in 0..PRODUCERS {
        let tx = tx.clone();
        ts.push(thread::spawn(move || {
            for i in 1..=ITEMS {
                tx.send(i).unwrap();
            }
        }));
    }
    drop(tx);
    let mut sum = 0u64;
    for v in rx {
        sum += v;
    }
    for t in ts {
        t.join().unwrap();
    }
    println!("{}", sum);
}
