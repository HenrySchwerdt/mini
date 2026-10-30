// fannkuch-redux: integer array permutations and flips. Same algorithm in every language.
const N: usize = 11;

fn main() {
    let mut perm = [0usize; N];
    let mut perm1 = [0usize; N];
    let mut count = [0usize; N];
    let mut max_flips = 0i32;
    let mut perm_count = 0i32;
    let mut checksum = 0i32;
    for i in 0..N {
        perm1[i] = i;
    }
    let mut r = N;
    loop {
        while r != 1 {
            count[r - 1] = r;
            r -= 1;
        }
        perm.copy_from_slice(&perm1);
        let mut flips = 0i32;
        loop {
            let k = perm[0];
            if k == 0 {
                break;
            }
            let k2 = (k + 1) >> 1;
            for i in 0..k2 {
                perm.swap(i, k - i);
            }
            flips += 1;
        }
        if flips > max_flips {
            max_flips = flips;
        }
        checksum += if perm_count % 2 == 0 { flips } else { -flips };
        loop {
            if r == N {
                println!("{}\nPfannkuchen({}) = {}", checksum, N, max_flips);
                return;
            }
            let p0 = perm1[0];
            for i in 0..r {
                perm1[i] = perm1[i + 1];
            }
            perm1[r] = p0;
            count[r] -= 1;
            if count[r] > 0 {
                break;
            }
            r += 1;
        }
        perm_count += 1;
    }
}
