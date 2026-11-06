package mini.backend

/*
 * `-Xlayout-stats`: per class, the object size, the padding bytes and the fields with their offsets, for the current layout
 * (Layout.kt) next to the old one (declaration order, padded superclass embedded). Written to stderr by minic-native.
 */
object LayoutStats {
    fun report(module: MirModule, ptrBytes: Int, out: (String) -> Unit) {
        val classes = HashMap<String, MirClass>()
        for (c in module.classes) classes[c.cName] = c
        val lay = ObjectLayouts(classes, ptrBytes)
        var totalNew = 0L
        var totalOld = 0L
        var padNew = 0L
        var padOld = 0L
        var n = 0
        var blocksNew = 0L
        var blocksOld = 0L
        var smaller = 0
        var blocksBits = 0L
        var smallerBits = 0
        var boolFields = 0
        out("layout-stats: class: size (old size) pad bytes (old) [field@offset (old offset)]")
        for (c in module.classes) {
            if (c.isInterface || c.external) continue
            val l = lay.of(c.cName)
            val o = lay.naive(c.cName)
            var used = lay.headerSize
            // the whole chain, root first
            val chain = ArrayList<MirClass>()
            var cur: MirClass? = c
            while (cur != null) {
                chain.add(0, cur)
                val s = cur.superCName
                cur = if (s == null) null else classes[s]
            }
            var externalSeen = false
            var nBool = 0
            for (k in chain) {
                if (k.external) {
                    if (!externalSeen) used += 2 * ptrBytes
                    externalSeen = true
                } else for (f in k.fields) {
                    used += lay.sizeOf(f.type)
                    if (f.type === TBoolean) nBool++
                }
            }
            val sb = StringBuilder()
            sb.append(c.qualifiedName).append(": ").append(l.size).append(" (").append(o.size).append(") pad ")
            sb.append(l.size - used).append(" (").append(o.size - used).append(") [")
            var first = true
            for (k in chain) {
                if (k.external) continue
                for (f in k.fields) {
                    if (!first) sb.append(", ")
                    first = false
                    sb.append(f.name).append("@").append(lay.fieldOffset(k.cName, f.name)).append(" (").append(lay.naive(k.cName).offsets[f.name] ?: -1).append(")")
                }
            }
            sb.append("]")
            out(sb.toString())
            totalNew += l.size
            totalOld += o.size
            padNew += l.size - used
            padOld += o.size - used
            // the small-object allocator hands out 16-byte granules (runtime/src/alloc_small.h)
            blocksNew += (l.size + 15) / 16 * 16
            blocksOld += (o.size + 15) / 16 * 16
            if ((l.size + 15) / 16 < (o.size + 15) / 16) smaller++
            // estimate: Boolean fields packed 8 to a byte (an upper bound on what packing could save)
            val packed = lay.alignUp(l.dataEnd - nBool + (nBool + 7) / 8, l.align)
            blocksBits += (packed + 15) / 16 * 16
            if ((packed + 15) / 16 < (l.size + 15) / 16) smallerBits++
            boolFields += nBool
            n++
        }
        out("layout-stats: $n classes, sum of instance sizes $totalNew B (old $totalOld B), padding $padNew B (old $padOld B)")
        out("layout-stats: allocator blocks (16 B granules): sum ${blocksNew} B (old ${blocksOld} B), $smaller classes drop a granule")
        out("layout-stats: Boolean fields: $boolFields; with bit packing (estimate) blocks would be $blocksBits B, $smallerBits more classes would drop a granule")
    }
}
