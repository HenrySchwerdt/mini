// graph: build a random directed graph (a Node with its own edge list) and run breadth-first searches over it.
// Output: reached count, distance sum and eccentricity per source.
const NODES: usize = 1_000_000;
const DEGREE: usize = 4;
const SOURCES: usize = 6;

struct Node {
    dist: i32,
    out: Vec<usize>,
}

struct Lcg(i64);

impl Lcg {
    fn next(&mut self, n: usize) -> usize {
        self.0 = (self.0 * 1103515245 + 12345) & 0x7fffffff;
        ((self.0 >> 8) as usize) % n
    }
}

fn main() {
    let mut rng = Lcg(7);
    let mut nodes: Vec<Node> = (0..NODES).map(|_| Node { dist: -1, out: Vec::new() }).collect();
    for i in 0..NODES {
        for _ in 0..DEGREE {
            let t = rng.next(NODES);
            nodes[i].out.push(t);
        }
    }
    for src in 0..SOURCES {
        for n in nodes.iter_mut() {
            n.dist = -1;
        }
        let mut queue: Vec<usize> = Vec::new();
        let start = src * 1009;
        nodes[start].dist = 0;
        queue.push(start);
        let mut head = 0;
        let mut sum = 0i64;
        let mut ecc = 0;
        while head < queue.len() {
            let n = queue[head];
            head += 1;
            let d = nodes[n].dist;
            sum += d as i64;
            if d > ecc {
                ecc = d;
            }
            for k in 0..nodes[n].out.len() {
                let m = nodes[n].out[k];
                if nodes[m].dist < 0 {
                    nodes[m].dist = d + 1;
                    queue.push(m);
                }
            }
        }
        println!("{} {} {}", queue.len(), sum, ecc);
    }
}
