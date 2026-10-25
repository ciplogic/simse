package bench_lru

// An LRU cache of capacity N/8 under N skewed lookups, with an invalidation
// every 16th operation, walking the recency list at the end so the order is
// checked too. Port of aardappel/goose bench/lru.goose. Goose uses a reusable
// pool with pool-relative references; the same program in safe shapes is an
// arena of nodes with a free list plus an open-addressed table with
// backward-shift deletion - the C++ expert row (bench/cpp/lru.cpp VARIANT 1).
// Its idiomatic row is std::list plus unordered_map.

import kit
import unsafe

val N: Int = 2000000 // BENCH_N

data class LNode(var key: Int, var value: Int, var prev: Int, var next: Int)
data class Slot(var key: Int, var idx: Int)

var pool: List<LNode> = List<LNode>()
var freelist: List<Int> = List<Int>()
var slots: List<Slot> = List<Slot>()
var mask: Int64 = 0
var lcount: Int = 0

// 0x9E3779B97F4A7C15, as the signed spelling of its bit pattern: the product
// wraps, the shift below is the logical one, and only the bits matter.
val FIB: UInt64 = UInt64(-7046029254386353131)

fun homeSlot(key: Int64): Int {
    return Int(UInt64(key).times(FIB).shr(24).andWith(UInt64(mask)).toInt64())
}

// The slot holding `key`, or the empty slot where it would go.
fun findSlot(key: Int64): Int {
    var i: Int = homeSlot(key)
    while (slots[i].idx >= 0 && slots[i].key != Int(key)) {
        i = (i + 1) & Int(mask)
    }
    return i
}

// Backward-shift deletion: slide later entries of the probe run down over the
// hole, so no tombstones accumulate.
fun mapRemove(at: Int): Unit {
    var i: Int = at
    var j: Int = at
    while (true) {
        j = (j + 1) & Int(mask)
        val s: Slot = slots[j]
        if (s.idx < 0) {
            break
        }
        val h: Int = homeSlot(Int64(s.key))
        if ((i < j && (h <= i || h > j)) || (i > j && h <= i && h > j)) {
            slots[i] = s
            i = j
        }
    }
    slots[i] = Slot(0, -1)
}

fun allocNode(key: Int, value: Int): Int {
    if (freelist.size() > 0) {
        val k: Int = freelist[freelist.size() - 1]
        freelist.removeAt(freelist.size() - 1)
        pool[k].key = key
        pool[k].value = value
        return k
    }
    pool.append(LNode(key, value, 0, 0))
    return pool.size() - 1
}

fun unlink(n: Int): Unit {
    val p: Int = pool[n].prev
    val q: Int = pool[n].next
    pool[p].next = q
    pool[q].prev = p
}

fun linkFront(head: Int, n: Int): Unit {
    val q: Int = pool[head].next
    pool[n].prev = head
    pool[n].next = q
    pool[q].prev = n
    pool[head].next = n
}

fun main(): Int {
    val cap: Int = N / 8
    val keys: Int = cap * 2
    var nslots: Int = 16
    while (nslots < cap * 2) {
        nslots *= 2
    }
    mask = Int64(nslots - 1)
    var si: Int = 0
    while (si < nslots) {
        slots.append(Slot(0, -1))
        si++
    }
    // Two sentinels: the list is never empty, so no link is ever null.
    val hi: Int = allocNode(-1, 0)
    val ti: Int = allocNode(-1, 0)
    pool[hi].next = ti
    pool[ti].prev = hi
    var hits: Int64 = 0
    var misses: Int64 = 0
    var removes: Int64 = 0
    var r: UInt64 = UInt64(12345)
    var op: Int = 0
    while (op < N) {
        r = xsNext(r)
        val a: Int64 = r.uMod(UInt64(keys)).toInt64()
        val key: Int64 = (a * a) / Int64(keys)
        val at: Int = findSlot(key)
        val fi: Int = slots[at].idx
        if (r.shr(40).uMod(UInt64(16)) == UInt64(0)) {
            // An invalidation: drop the entry if it is cached.
            if (fi >= 0) {
                unlink(fi)
                mapRemove(at)
                freelist.append(fi)
                lcount--
                removes += 1
            }
            op++
            continue
        }
        if (fi >= 0) {
            pool[fi].value += 1
            unlink(fi)
            linkFront(hi, fi)
            hits += 1
            op++
            continue
        }
        misses += 1
        var ins: Int = at
        if (lcount == cap) {
            val lru: Int = pool[ti].prev
            unlink(lru)
            val lat: Int = findSlot(Int64(pool[lru].key))
            val li: Int = slots[lat].idx
            mapRemove(lat)
            freelist.append(li)
            lcount--
            // The deletion may have shifted entries in this key's probe run,
            // so the empty slot found above can be stale.
            ins = findSlot(key)
        }
        val ni: Int = allocNode(Int(key), Int(key % 1000))
        slots[ins] = Slot(Int(key), ni)
        linkFront(hi, ni)
        lcount++
        op++
    }
    // Walk the list from most to least recent, so the order is checked too.
    var walk: UInt64 = UInt64(0)
    var vals: Int64 = 0
    var cur: Int = pool[hi].next
    while (pool[cur].key >= 0) {
        walk = walk.times(UInt64(31)).plus(UInt64(pool[cur].key))
        vals += Int64(pool[cur].value)
        cur = pool[cur].next
    }
    println(hits)
    println(misses)
    println(removes)
    println(lcount)
    println(walk.toString())
    println(vals)
    return 0
}
