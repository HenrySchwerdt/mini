// binary-trees: allocation heavy (Box per node). Same algorithm in every language.
const MAX_DEPTH: i32 = 18;

struct Node {
    left: Option<Box<Node>>,
    right: Option<Box<Node>>,
}

fn bottom_up(d: i32) -> Box<Node> {
    if d == 0 {
        Box::new(Node { left: None, right: None })
    } else {
        Box::new(Node { left: Some(bottom_up(d - 1)), right: Some(bottom_up(d - 1)) })
    }
}

fn check(n: &Node) -> i32 {
    match &n.left {
        None => 1,
        Some(l) => 1 + check(l) + check(n.right.as_ref().unwrap()),
    }
}

fn main() {
    let min_depth = 4;
    let stretch = MAX_DEPTH + 1;
    println!("stretch tree of depth {}\t check: {}", stretch, check(&bottom_up(stretch)));
    let long_lived = bottom_up(MAX_DEPTH);
    let mut d = min_depth;
    while d <= MAX_DEPTH {
        let iters = 1 << (MAX_DEPTH - d + min_depth);
        let mut chk = 0;
        for _ in 0..iters {
            chk += check(&bottom_up(d));
        }
        println!("{}\t trees of depth {}\t check: {}", iters, d, chk);
        d += 2;
    }
    println!("long lived tree of depth {}\t check: {}", MAX_DEPTH, check(&long_lived));
}
