// json-like: generate a JSON document from a seeded generator, parse it into a tree (recursive descent, strings with escapes,
// objects as key/value lists), walk the tree. Output: text length, node count, checksum of the walk.
use std::fmt::Write;

const TOP: usize = 60000;
const REPS: usize = 5;
const MOD: i64 = 1_000_000_007;

struct Rng(i64);

impl Rng {
    fn next(&mut self) -> i32 {
        self.0 = (self.0 * 1103515245 + 12345) & 0x7fffffff;
        (self.0 >> 8) as i32
    }
}

fn leaf(sb: &mut String, rng: &mut Rng) {
    match rng.next() % 6 {
        0 => sb.push_str("null"),
        1 => sb.push_str("true"),
        2 => sb.push_str("false"),
        3 => {
            let v = rng.next() % 200000 - 100000;
            write!(sb, "{}", v).unwrap();
        }
        4 => {
            sb.push_str("\"s");
            let v = rng.next() % 5000;
            write!(sb, "{}", v).unwrap();
            sb.push('"');
        }
        _ => {
            sb.push_str("\"a\\nb\\\"c");
            let v = rng.next() % 5000;
            write!(sb, "{}", v).unwrap();
            sb.push_str("\\\\\"");
        }
    }
}

fn gen(sb: &mut String, rng: &mut Rng, depth: i32) {
    let k = rng.next() % 12;
    if depth >= 6 || k < 5 {
        leaf(sb, rng);
    } else if k < 8 {
        let n = 1 + rng.next() % 4;
        sb.push('{');
        for i in 0..n {
            if i > 0 {
                sb.push_str(", ");
            }
            sb.push_str("\"k");
            let v = rng.next() % 1000;
            write!(sb, "{}", v).unwrap();
            sb.push_str("\": ");
            gen(sb, rng, depth + 1);
        }
        sb.push('}');
    } else {
        let n = rng.next() % 5;
        sb.push('[');
        for i in 0..n {
            if i > 0 {
                sb.push_str(", ");
            }
            gen(sb, rng, depth + 1);
        }
        sb.push(']');
    }
}

enum J {
    Null,
    Bool(bool),
    Num(i64),
    Str(String),
    Arr(Vec<J>),
    Obj(Vec<(String, J)>),
}

struct Parser<'a> {
    s: &'a [u8],
    pos: usize,
}

impl<'a> Parser<'a> {
    fn skip_ws(&mut self) {
        while self.pos < self.s.len() && self.s[self.pos] == b' ' {
            self.pos += 1;
        }
    }

    fn parse_string(&mut self) -> String {
        self.pos += 1;
        let start = self.pos;
        let mut esc = false;
        while self.s[self.pos] != b'"' {
            if self.s[self.pos] == b'\\' {
                esc = true;
                self.pos += 1;
            }
            self.pos += 1;
        }
        let raw = &self.s[start..self.pos];
        self.pos += 1;
        if !esc {
            return String::from_utf8_lossy(raw).into_owned();
        }
        let mut out = String::new();
        let mut i = 0;
        while i < raw.len() {
            let c = raw[i];
            if c == b'\\' {
                i += 1;
                let d = raw[i];
                out.push(if d == b'n' { '\n' } else { d as char });
            } else {
                out.push(c as char);
            }
            i += 1;
        }
        out
    }

    fn parse_value(&mut self) -> J {
        self.skip_ws();
        let c = self.s[self.pos];
        if c == b'{' {
            self.pos += 1;
            let mut members = Vec::new();
            self.skip_ws();
            if self.s[self.pos] == b'}' {
                self.pos += 1;
                return J::Obj(members);
            }
            loop {
                self.skip_ws();
                let k = self.parse_string();
                self.skip_ws();
                self.pos += 1;
                let v = self.parse_value();
                members.push((k, v));
                self.skip_ws();
                let d = self.s[self.pos];
                self.pos += 1;
                if d == b'}' {
                    break;
                }
            }
            return J::Obj(members);
        }
        if c == b'[' {
            self.pos += 1;
            let mut items = Vec::new();
            self.skip_ws();
            if self.s[self.pos] == b']' {
                self.pos += 1;
                return J::Arr(items);
            }
            loop {
                items.push(self.parse_value());
                self.skip_ws();
                let d = self.s[self.pos];
                self.pos += 1;
                if d == b']' {
                    break;
                }
            }
            return J::Arr(items);
        }
        if c == b'"' {
            return J::Str(self.parse_string());
        }
        if c == b't' {
            self.pos += 4;
            return J::Bool(true);
        }
        if c == b'f' {
            self.pos += 5;
            return J::Bool(false);
        }
        if c == b'n' {
            self.pos += 4;
            return J::Null;
        }
        let mut neg = false;
        if c == b'-' {
            neg = true;
            self.pos += 1;
        }
        let mut v = 0i64;
        while self.pos < self.s.len() && self.s[self.pos].is_ascii_digit() {
            v = v * 10 + (self.s[self.pos] - b'0') as i64;
            self.pos += 1;
        }
        J::Num(if neg { -v } else { v })
    }
}

struct Stats {
    h: i64,
    n: i64,
}

impl Stats {
    fn step(&mut self, x: i64) {
        self.h = (self.h * 31 + x) % MOD;
        self.n += 1;
    }
}

fn walk(j: &J, st: &mut Stats) {
    match j {
        J::Null => st.step(1),
        J::Bool(b) => st.step(if *b { 2 } else { 3 }),
        J::Num(v) => st.step(*v + 100000),
        J::Str(s) => {
            let mut t = s.len() as i64;
            for b in s.bytes() {
                t += b as i64;
            }
            st.step(t);
        }
        J::Arr(items) => {
            st.step(13 + items.len() as i64);
            for it in items {
                walk(it, st);
            }
        }
        J::Obj(members) => {
            st.step(11 + members.len() as i64);
            for (k, v) in members {
                st.step(k.len() as i64);
                walk(v, st);
            }
        }
    }
}

fn main() {
    let mut rng = Rng(7);
    let mut sb = String::new();
    sb.push('[');
    for i in 0..TOP {
        if i > 0 {
            sb.push_str(", ");
        }
        gen(&mut sb, &mut rng, 1);
    }
    sb.push(']');
    let (mut n, mut h) = (0, 0);
    for _ in 0..REPS {
        let mut p = Parser { s: sb.as_bytes(), pos: 0 };
        let tree = p.parse_value();
        let mut st = Stats { h: 0, n: 0 };
        walk(&tree, &mut st);
        n = st.n;
        h = st.h;
    }
    println!("{} {} {}", sb.len(), n, h);
}
