package mini.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Git dependencies against a local bare repository used as the remote. */
class GitDependencyTest {
    companion object {
        /** Creates `<root>/remote.git` (bare) holding a library `json` with the tag v1.0; returns (work tree, bare url). */
        fun makeRemote(root: File, libSource: String = "package json\n\nfun parse(s: String): Int = s.length\n"): Pair<File, String> {
            val work = File(root, "json-work").also { it.mkdirs() }
            runGit(work, "init", "-q")
            File(work, "mini.toml").writeText("[package]\nname = \"json\"\nkind = \"lib\"\nversion = \"1.0.0\"\n")
            File(work, "src").mkdirs()
            File(work, "src/lib.kt").writeText(libSource)
            runGit(work, "add", ".")
            runGit(work, "commit", "-q", "-m", "v1")
            runGit(work, "tag", "v1.0")
            val bare = File(root, "remote.git")
            runGit(root, "clone", "-q", "--bare", work.path, bare.path)
            return Pair(work, bare.path)
        }
    }

    private fun app(p: TestPlatform): TestPlatform {
        p.write("app/mini.toml", "[package]\nname = \"app\"\nkind = \"app\"\n")
        p.write("app/src/main.kt", "fun main() {}\n")
        p.dir = File(p.root, "app")
        return p
    }

    @Test
    fun addGitDependencyPinsACommitAndWorksOffline() {
        if (!(TestPlatform.gitAvailable())) return
        val p = app(TestPlatform.temp())
        val (work, url) = makeRemote(p.root)
        val commit = runGit(work, "rev-parse", "HEAD")

        assertEquals(0, p.mini("add", "--git", url, "--tag", "v1.0"), p.err.toString())
        assertTrue(p.file("mini.toml").readText().contains("json = { git = \"$url\", tag = \"v1.0\" }"), p.file("mini.toml").readText())
        assertTrue(p.err.contains("Cloning git repository"), p.err.toString())

        val lock = p.file("mini.lock").readText()
        assertTrue(lock.contains("[git.json]") && lock.contains("spec = \"tag:v1.0\"") && lock.contains("commit = \"$commit\""), lock)

        p.reset()
        assertEquals(0, p.mini("tree"))
        assertTrue(p.out.contains("json v1.0.0 (git $url#${commit.take(7)})"), p.out.toString())
        assertTrue(File(p.root, "home/git/checkouts").isDirectory)

        // deterministic: a second resolution leaves the lock file byte-identical and does not touch the network
        val before = p.file("mini.lock").readText()
        p.reset()
        File(url).deleteRecursively() // the remote disappears; the cache is enough
        assertEquals(0, p.mini("tree", "--offline"), p.err.toString())
        assertEquals(0, p.mini("tree", "--locked"), p.err.toString())
        assertEquals(before, p.file("mini.lock").readText())
        assertTrue(!p.err.contains("Cloning") && !p.err.contains("Updating"), p.err.toString())
    }

    @Test
    fun updateMovesBranchPinsButTagsStayPut() {
        if (!(TestPlatform.gitAvailable())) return
        val p = app(TestPlatform.temp())
        val (work, url) = makeRemote(p.root)
        val first = runGit(work, "rev-parse", "HEAD")
        p.write("mini.toml", "[package]\nname = \"app\"\nkind = \"app\"\n[dependencies]\njson = { git = \"$url\" }\n")
        assertEquals(0, p.mini("tree"), p.err.toString())
        assertTrue(p.file("mini.lock").readText().contains(first))

        // upstream moves on
        File(work, "src/lib.kt").appendText("\nfun extra() {}\n")
        runGit(work, "commit", "-q", "-am", "v2")
        runGit(work, "push", "-q", url, "HEAD:main")
        val second = runGit(work, "rev-parse", "HEAD")
        assertNotEquals(first, second)

        p.reset()
        assertEquals(0, p.mini("tree"))
        assertTrue(p.file("mini.lock").readText().contains(first), "a pinned dependency does not move by itself")

        p.reset()
        assertEquals(0, p.mini("update"), p.err.toString())
        assertTrue(p.err.contains("Updating json ${first.take(7)} -> ${second.take(7)}"), p.err.toString())
        assertTrue(p.file("mini.lock").readText().contains(second))

        // changing the manifest (default -> tag) invalidates the pin; --locked then refuses to guess
        p.write("mini.toml", "[package]\nname = \"app\"\nkind = \"app\"\n[dependencies]\njson = { git = \"$url\", tag = \"v1.0\" }\n")
        p.reset()
        assertEquals(1, p.mini("tree", "--locked"))
        assertTrue(p.err.contains("not pinned in mini.lock") && p.err.contains("--> mini.toml:5:1"), p.err.toString())
        p.reset()
        assertEquals(0, p.mini("tree"))
        assertTrue(p.file("mini.lock").readText().contains(first))
    }

    @Test
    fun unknownTagIsReportedWithASuggestion() {
        if (!(TestPlatform.gitAvailable())) return
        val p = app(TestPlatform.temp())
        val (_, url) = makeRemote(p.root)
        p.write("mini.toml", "[package]\nname = \"app\"\nkind = \"app\"\n[dependencies]\njson = { git = \"$url\", tag = \"v1.1\" }\n")
        assertEquals(1, p.mini("tree"))
        assertTrue(p.err.contains("has no tag `v1.1`") && p.err.contains("did you mean tag `v1.0`?"), p.err.toString())
    }

    @Test
    fun unreachableRepositoryAndOffline() {
        if (!(TestPlatform.gitAvailable())) return
        val p = app(TestPlatform.temp())
        p.write("mini.toml", "[package]\nname = \"app\"\nkind = \"app\"\n[dependencies]\njson = { git = \"${p.root}/nope.git\" }\n")
        assertEquals(1, p.mini("tree"))
        assertTrue(p.err.contains("cloning for git dependency") && p.err.contains("check the URL"), p.err.toString())
        p.reset()
        assertEquals(1, p.mini("tree", "--offline"))
        assertTrue(p.err.contains("not in the local cache") && p.err.contains("without `--offline`"), p.err.toString())
    }
}
