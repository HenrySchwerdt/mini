package mini.minicnative

import mini.frontend.AstCodec
import mini.lower.Mlib
import mini.lower.MlibError
import mini.lower.MlibHasher
import mini.lower.MlibStub
import mini.frontend.KtFile
import mini.frontend.parseKotlinUnquoted
import mini.io.createDirectories
import mini.io.readTextFile
import mini.io.writeTextFile

/*
 * The stub cache (docs/mlib.md, "Stub cache"): the parsed trees of the API stubs of a precompiled library, kept in a directory
 * between compiler runs, so that a program that uses the library does not lex and parse its declarations again (for the standard
 * library: 130 files, the largest cost of `minic check`). One file per library name, `<dir>/stubs-<hash of the name>.txt`:
 *
 *     mini-stubcache <key> <length of the body>
 *     <one hash line per stub: hash of its path and text>
 *     <body: AstCodec encoding of the trees>
 *
 * The key covers everything the trees depend on: the cache and encoding format, the identity of the compiler build that wrote it
 * ([buildId]: the AST and parser classes are part of the compiler), the compiler id and the content hash of the library. The
 * stub texts themselves are re-hashed on every load, so a library file edited behind the header's back misses. A damaged or
 * truncated cache file (decoding fails, lengths differ) is a miss, never an error: the cache is only ever an optimization, and
 * whatever happens here the analysis gets exactly the trees the parser would have made.
 */
const val STUB_CACHE_FORMAT = 1

class StubCache(val dir: String, val buildId: String, val verify: Boolean = false) {
    private fun nameHash(lib: Mlib): String = MlibHasher().add(lib.name).hex()

    fun fileOf(lib: Mlib): String = (if (dir.endsWith("/")) dir else "$dir/") + "stubs-" + nameHash(lib) + ".txt"

    fun keyOf(lib: Mlib): String =
        MlibHasher().add("stub-cache").add(STUB_CACHE_FORMAT.toString()).add(AstCodec.VERSION.toString()).add(buildId)
            .add(lib.compilerId).add(lib.name).add(lib.hash).hex()

    /** Identifies the text of a stub: its length and the 32-bit hash of the string (cheap: this runs on every load); the library header hash is in the key too. */
    private fun stubHash(s: MlibStub): String = s.path.hashCode().toString() + ":" + s.text.length + ":" + s.text.hashCode()

    /**
     * Gives every stub of [lib] its parsed tree: from the cache file when it is valid (returns true), else by parsing them now and
     * writing the cache (returns false). A library with a stub that has syntax errors is left alone (the analysis reports them).
     */
    fun prepare(lib: Mlib): Boolean {
        val trees = load(lib)
        if (trees != null) {
            if (verify) checkAgainstParser(lib, trees)
            for (i in 0 until lib.stubs.size) lib.stubs[i].parsed = trees[i]
            return true
        }
        val fresh = parseAll(lib) ?: return false
        for (i in 0 until lib.stubs.size) lib.stubs[i].parsed = fresh[i]
        store(lib, fresh)
        return false
    }

    private fun parseAll(lib: Mlib): List<KtFile>? {
        val out = ArrayList<KtFile>()
        for (s in lib.stubs) {
            val r = parseKotlinUnquoted(s.text)
            if (r.errors.isNotEmpty()) return null
            out.add(r.file)
        }
        return out
    }

    private fun checkAgainstParser(lib: Mlib, cached: List<KtFile>) {
        val fresh = parseAll(lib) ?: throw MlibError("stub cache check: the stubs of library '${lib.name}' do not parse")
        if (AstCodec.encode(fresh) != AstCodec.encode(cached)) {
            throw MlibError("stub cache check failed for library '${lib.name}' (${fileOf(lib)}): the cached trees differ from a fresh parse; delete the cache directory $dir")
        }
    }

    private fun load(lib: Mlib): List<KtFile>? {
        val text = try {
            readTextFile(fileOf(lib))
        } catch (e: Exception) {
            return null
        }
        try {
            var pos = 0
            fun line(): String? {
                val e = text.indexOf('\n', pos)
                if (e < 0) return null
                val l = text.substring(pos, e)
                pos = e + 1
                return l
            }
            val head = (line() ?: return null).split(' ')
            if (head.size != 3 || head[0] != "mini-stubcache" || head[1] != keyOf(lib)) return null
            val bodyLength = head[2].toIntOrNull() ?: return null
            for (s in lib.stubs) if (line() != stubHash(s)) return null
            if (text.length - pos != bodyLength) return null
            val trees = AstCodec.decode(text, pos) ?: return null
            if (trees.size != lib.stubs.size) return null
            return trees
        } catch (e: Exception) {
            return null
        }
    }

    private fun store(lib: Mlib, trees: List<KtFile>) {
        try {
            val body = AstCodec.encode(trees)
            val sb = StringBuilder()
            sb.append("mini-stubcache ").append(keyOf(lib)).append(' ').append(body.length).append('\n')
            for (s in lib.stubs) sb.append(stubHash(s)).append('\n')
            sb.append(body)
            createDirectories(dir)
            writeTextFile(fileOf(lib), sb.toString())
        } catch (e: Exception) {
            // best effort: a read-only or full cache directory only costs the next run its parse
        }
    }
}
