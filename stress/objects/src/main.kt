package objects

// ---- counted-reference ----
// The counted reference's *sharing* (`&T`, specs/memory-model.md): `&value` boxes a copy,
// copying a handle adds an owner of the same box, a write through one handle is seen
// through the other, and a handle that ends does not free a box another one holds. That
// counting is what the RTL implements (`cppsrc/rtl/ref.hpp`: `SmRef`, or the
// `std::shared_ptr` shim) and what nothing else in the corpus observes.
//
// `stress/language-tour` covers the rest of the operator: null handles, the null tests, and
// `*T` taken from a handle. This case also pins the `Ref<T>` spelling the emitter writes for
// `&T` in a *program* (its `expected.cpp`), which no other case does.

data class Counter(var value: Int) {
    fun bump(): Int {
        this.value = this.value + 1
        return this.value
    }
}

fun read(box: &Counter): Int {
    return box.value
}

// A handle taken as a parameter is a second owner of the box, and one taken again inside is
// a third: the box outlives this call because `bumpThrough`'s caller still holds one.
fun bumpThrough(box: &Counter): Int {
    val again: &Counter = box
    return again.bump()
}

fun partCountedReference(): Int {
    val plain: Counter = Counter(10)

    // `&plain` boxes a *copy*: the box and `plain` are two values, so a write through the
    // handle is not visible in `plain`.
    val first: &Counter = &plain
    println(read(first))
    val bumped: Int = first.bump()
    println(bumped)
    println(plain.value)

    // A copy of the handle is another owner of the same box.
    println(first.value)
    println(bumpThrough(first))

    // The handles taken above have ended; the box has not.
    println(first.value)
    return 0
}

// ---- generics ----
data class Pair<A, B>(var first: A, var second: B)

fun identity<T>(value: T): T {
    return value
}

fun partGenerics(): Int {
    val numbers: List<Int> = List<Int>()
    val vector: SmallVector<4, Int> = SmallVector<4, Int>()
    val a: Pair<Int, Bool> = Pair<Int, Bool>(1, true)
    val b: Pair<Str, Int> = Pair<Str, Int>("two", 2)
    println(identity<Int>(7))
    println(a.first)
    println(b.second)
    println(numbers.size())
    return 0
}

// ---- hello ----
data class CounterHello(var value: Int) {
    fun bump(): Int {
        return this.value + 1
    }
}

fun partHello(): Int {
    var i: Int = 0
    var total: Int = 0
    while (i < 5) {
        total = total + i
        i = i + 1
    }
    val c: CounterHello = CounterHello(10)
    if (total > 5) {
        println(total)
    } else {
        println(c.bump())
    }
    println(true)
    return 0
}

// ---- lambdas ----
// T18: lambdas assigned to callable values and passed as arguments.

typealias Mapper = (Int) -> Int
typealias Predicate = (Int) -> Bool

fun apply(f: Mapper, value: Int): Int {
    return f(value)
}

fun countIf(items: *List<Int>, predicate: Predicate): Int {
    var count: Int = 0
    var c: Span<Int> = spanOf(items)
    while (!c.isEmpty()) {
        if (predicate(c[0])) {
            count = count + 1
        }
        c = c.slice(1)
    }
    return count
}

fun makeAdder(factor: Int): Mapper {
    return (v: Int) -> v + factor
}

fun partLambdas(): Int {
    val doubler: Mapper = (v: Int) -> v * 2
    println(doubler(21))
    println(apply(doubler, 5))

    // A lambda passed directly as an argument.
    println(apply((v: Int) -> v + 1, 41))

    // A closure capturing a local by value.
    val add10: Mapper = makeAdder(10)
    println(add10(5))

    var items: List<Int> = List<Int>()
    items.append(1)
    items.append(2)
    items.append(3)
    items.append(4)

    val isEven: Predicate = (v: Int) -> v % 2 == 0
    println(countIf(*items, isEven))
    println(countIf(*items, (v: Int) -> v > 2))

    // A block-bodied lambda.
    val big: Mapper = (v: Int) -> {
        if (v > 0) {
            return v * 100
        }
        return 0
    }
    println(big(3))

    return 0
}

// ---- language-tour ----
// T14: exercises `when`, null nullability, the Str/Char library, numeric
// conversions, min/max, and enum toInt/fromInt.

enum class Color {
    Red,
    Green = 4,
    Blue
}

data class Box(var value: Int)

fun label(c: Color): Str {
    when (c) {
        Color.Red -> {
            return "red"
        }

        Color.Green -> {
            return "green"
        }

        else -> {
            return "other"
        }
    }
}

fun describe(n: Int): Str {
    when (n) {
        0 -> {
            return "zero"
        }

        1 -> {
            return "one"
        }

        else -> {
            return "many"
        }
    }
}

fun maybeRef(flag: Bool): &Box {
    if (flag) {
        return &Box(7)
    }
    return null
}

fun maybePointer(box: &Box, flag: Bool): *Box {
    if (flag) {
        return * box
    }
    return null
}

fun partLanguageTour(): Int {
    println(label(Color.Red))
    println(label(Color.Green))
    println(label(Color.Blue))
    println(describe(0))
    println(describe(5))

    println(Color.Green.toInt())
    val parsed: Color = Color.fromInt(4)
    println(label(parsed))
    // `fromInt` is the direct cast back (`specs/declarations.md`): an enum's runtime
    // representation is an `Int`, so an integer that names no member is still that value.
    println(Color.fromInt(5).toInt())

    val present: &Box = maybeRef(true)
    if (present != null) {
        println(present.value)
    }
    val absent: &Box = maybeRef(false)
    if (absent == null) {
        println("absent")
    }
    val boxed: &Box = &Box(3)
    val rawPresent: *Box = maybePointer(boxed, true)
    if (rawPresent != null) {
        println(rawPresent.value)
    }
    val rawAbsent: *Box = maybePointer(boxed, false)
    if (rawAbsent == null) {
        println("null raw")
    }

    val text: Str = "hello world"
    println(text.find("world"))
    println(text.find("zzz"))
    println(text.substr(0, 5))
    println(text.startsWith("hello"))
    println(text.endsWith("world"))
    println(text.replace("world", "simse"))
    val number: Opt<Int> = "42".toInt()
    if (number.hasValue()) {
        println(number.value())
    }
    val badNumber: Opt<Int> = "nope".toInt()
    if (!badNumber.hasValue()) {
        println("bad int")
    }
    val fraction: Opt<Float64> = "2.5".toFloat()
    if (fraction.hasValue()) {
        println(fraction.value())
    }

    val letter: Char = 'a'
    println(letter.isAlpha())
    println(letter.isDigit())
    println('7'.isDigit())
    println(' '.isSpace())

    val small: Int8 = 5
    println(small.toString())
    println(true.toString())
    println(2.5.toString())

    println(min(3, 9))
    println(max(3, 9))

    return 0
}

// ---- optional-result ----
// `Opt<T>` and `Res<T>` are one storage type with two arms - `Variant2<T, VoidEnum>` and
// `Variant2<T, Str>` (cppsrc/rtl/variant2.hpp) - so this case pins the states and the
// operations that union has to support:
//
//   pick     - an empty optional built by `null` (the default-constructed arm, which
//              `Codegen.nullTo` emits as `Opt<T>()`) and by `none()`, and one that
//              carries text (`Opt<Str>`, an owning `Str` *inside* the union);
//   label    - a result whose message is empty: `err("")` is a failure now that the tag,
//              and not the message's length, says which arm is live;
//   main     - copies and re-assignments of both (a slot declared first and assigned
//              later, which is what the emitter's temporaries are), and a container of
//              them (`List<Opt<Int>>`).

fun half(n: Int): Opt<Int> {
    if (n % 2 != 0) {
        return Opt<Int>.none()
    }
    return Opt<Int>.some(n / 2)
}

fun labelOptionalResult(n: Int): Res<Str> {
    if (n < 0) {
        return Res<Str>.err("")
    }
    return Res<Str>.ok("positive")
}

// A message longer than the inline buffer, so the union's error arm owns a heap block:
// copying a failed result copies it through the union.
fun longError(): Res<Int> {
    return Res<Int>.err("a message that does not fit in the inline buffer")
}

fun echo(r: Res<Int>): Res<Int> {
    val copy: Res<Int> = r
    if (!copy.isOk()) {
        return Res<Int>.err(copy.Error)
    }
    return Res<Int>.ok(copy.Value)
}

fun partOptionalResult(): Int {
    // `null` in an `Opt<T>` context is the empty optional.
    val empty: Opt<Int> = null
    println(empty.hasValue())

    var found: Opt<Int> = half(8)
    println(found.value())
    // Re-assignment replaces the live arm.
    found = half(9)
    println(found.hasValue())
    found = Opt<Int>.some(3)
    println(found.value())

    // A payload that owns storage, copied out of the union.
    var text: Opt<Str> = Opt<Str>.some("hello")
    val copy: Opt<Str> = text
    println(copy.value().size())
    text = Opt<Str>.none()
    println(text.hasValue())
    println(copy.value())

    // An empty message is still a failure.
    val bad: Res<Str> = labelOptionalResult(-1)
    println(bad.isOk())
    println(bad.Error.size())
    val good: Res<Str> = labelOptionalResult(7)
    println(good.isOk())
    println(good.Value)

    // The two arms inside one container.
    var counts: List<Opt<Int>> = List<Opt<Int>>()
    counts.append(half(4))
    counts.append(half(5))
    println(counts[0].value())
    println(counts[1].hasValue())

    // A failed result copied and handed back: the message owns storage, so the copy
    // crosses the union's arms and the payload arm is never built.
    val failed: Res<Int> = longError()
    println(failed.isOk())
    val again: Res<Int> = echo(failed)
    println(again.Error)
    println(echo(Res<Int>.ok(9)).Value)

    return 0
}

// ---- pointer-place ----
// A `*T` parameter takes the *argument's* address, so what the callee writes is what
// the argument named - a local, an element of a list, a field of an object. The
// conversion the compiler does at the call (`specs/functions.md`, "Handles at a
// call") has to keep a place a place: binding `cells[0]` to a temporary first would
// hand the callee the address of a copy and the write would be lost (silently - the
// emitted C++ still compiles).

data class Cell(var value: Int)

data class Grid(var cell: Cell)

fun bump(value: *Int): Unit {
    *value = * value +1
}

fun bumpCell(cell: *Cell): Unit {
    cell.value = cell.value + 1
}

fun partPointerPlace(): Int {
    // A local.
    var local: Int = 1
    bump(local)
    println(local.toString())             // 2

    // An element of a list: the list is the caller's, and it changes.
    val cells: List<Cell> = listOf(Cell(1), Cell(2))
    bumpCell(cells[0])
    println(cells[0].value.toString())    // 2

    // A field of an object.
    val grid: Grid = Grid(Cell(5))
    bumpCell(grid.cell)
    println(grid.cell.value.toString())   // 6

    // A field of an element: two levels, still the caller's storage.
    bump(cells[1].value)
    println(cells[1].value.toString())    // 3

    // The same call written with an explicit `*` is the same address, not a second
    // copy: both spellings reach the caller's value.
    bump(*local)
    println(local.toString())             // 3
    return 0
}

// ---- receiver-shapes ----
// The receiver of a method whose receiver is a *value* is the raw pointer the call
// site passed (`T* self`, the shape `impl_specs/rtl-abi.md` records), and the three
// ways a receiver reaches a call are three different spellings:
//
//   - a method that calls another method on `this` passes that pointer as it is -
//     `ns1_add(self, 1)`. Spelling it `simse_addressOf((*self))` (a dereference and
//     then the address of the dereference) is the same pointer, but it *reads* like a
//     copy of the whole receiver at every self-call;
//   - `*this` (a borrow of the receiver) is the same pointer, so it is `self` too;
//   - a call on a *place* - a local, a field, an element - still takes the place's
//     address (`simse_addressOf(holder.counter)`), which is the rule
//     `stress/pointer-place` pins from the other side: a receiver that is not `this`
//     must reach the caller's storage.
//
// The program is trivial on purpose - what this case decides is the shape of the
// emitted C++ at those call sites, which `expected.cpp` locks byte for byte.

data class CounterReceiverShapes(var n: Int) {
    fun bump(): Unit {
        this.add(1)
    }

    fun add(delta: Int): Unit {
        this.n = this.n + delta
    }

    fun twice(): Int {
        this.bump()
        this.bump()
        return this.n
    }

    fun self(): *CounterReceiverShapes {
        return *this
    }
}

data class Holder(var counter: CounterReceiverShapes)

fun bumpTwice(counter: *CounterReceiverShapes): Unit {
    counter.bump()
    counter.bump()
}

fun partReceiverShapes(): Int {
    // Two self-calls reach the receiver the caller named, not a copy of it.
    val counter: CounterReceiverShapes = CounterReceiverShapes(0)
    println(counter.twice().toString())           // 2

    // A borrow of the receiver is the receiver: the write lands in `counter`.
    val through: *CounterReceiverShapes = counter.self()
    through.add(3)
    println(counter.n.toString())                 // 5

    // A receiver that is a field of a local object.
    val holder: Holder = Holder(CounterReceiverShapes(10))
    holder.counter.bump()
    println(holder.counter.n.toString())          // 11

    // ... and one that is a pointer parameter.
    bumpTwice(*counter)
    println(counter.n.toString())                 // 7
    return 0
}

// ---- rtl-simse ----
// The runtime operations whose bodies the *language* writes, not C++
// (`impl_specs/rtl-abi.md`, T70/T71): `min`, `max`, `fmtStr` and `Str.isEmpty`. They are
// prelude functions with a body, so each is emitted only when a program reaches it -
// which is also why this case exists: nothing in the compiler calls `min`/`max`, so
// without a case here their bodies would never be compiled at all.
//
// What each one pins:
//
//   - `min`/`max` are **generic** (`fun min<T>(a: T, b: T): T`), so they work for any
//     type with `<`: the instantiations below are `Int`, `Str` and `Float64`;
//   - the result of an **inferred** instantiation (`min(3, 2)`) is typed `T` at the call
//     site - the type pass substitutes the arguments of an *explicit* instantiation only
//     (`sema/TypeInfer.kt`, `functionReturn`) - so a destination has to state the type,
//     or the call has to name it: `min<Int>(3, 2)`. Both spellings are here, and the
//     inferred one needs a member call to go through a typed local.
//   - `fmtStr` fills one item per `|`, and a call whose points and items do not line up
//     (or that passes no items) gets the format back *unfilled* rather than a
//     half-filled result - the one shape it does not handle is the one it refuses.
//   - an **item that is a handle is read through to its value**, exactly as a by-value
//     parameter's argument is (`specs/functions.md`, "Handles at a call"): a pack stores
//     values and never pointers. That is the shape the emitter's own `fmtStr` calls pass,
//     since `xmlAttr` answers a `*Str`.
//   - `Str.isEmpty` is a prelude body with a **receiver**: the receiver-type form
//     (`fun Str.isEmpty()`) is what marks it an extension - a `native` spells the
//     receiver as an explicit `this`, a function with a body cannot. Its call sites
//     below are the shares a receiver has: a literal, a local, and a member call on
//     `this` inside the body of a receiver function of its own.

// A handle item is read through to its value, the way a by-value parameter's argument is
// (`specs/functions.md`, "Handles at a call"): a pack of items stores values, never pointers.
fun mirror(a: *Str, b: &Str): Str {
    return fmtStr("[|::|]", a, b)
}

// The pack a call builds (`specs/functions.md`): the trailing arguments become one list,
// each one a value - so a handle among them is read through here too.
fun parcel(after: Str, items: *List<Str>): Str {
    var out: Str = after
    var i: Int = 0
    while (i < items.size()) {
        out = out + "+" + items[i]
        i = i + 1
    }
    return out
}

fun partRtlSimse(): Int {
    // The inferred form: the destination spells the type.
    val least: Int = min(3, 2)
    val most: Int = max(3, 2)
    println(least.toString())                     // 2
    println(most.toString())                      // 3

    // The explicit form: the call names the type, so the result is typed anywhere.
    println(min<Str>("b", "a"))                   // a
    println(max<Str>("b", "a"))                   // b
    println(min<Float64>(2.5, 1.5).toString())    // 1.5

    // One item per point, in order, `||` included (an empty run between two points).
    println(fmtStr("[|::|]", "a", "b"))           // [a::b]
    println(fmtStr("|,|,|", "1", "2", "3"))       // 1,2,3
    println(fmtStr("||", "x", "y"))               // xy

    // The shape that does not line up comes back as the format, unfilled.
    println(fmtStr("no points", "x"))             // no points
    println(fmtStr("| |", "only-one"))            // | |

    // `Str.isEmpty` is the language's own body too (T71). Its receiver is an ordinary
    // parameter in the generated function (`Str* self`), so the call passes the
    // receiver's address - a local, and a field through `this`.
    println("a".isEmpty())                        // false
    println("".isEmpty())                        // true
    val text: Str = "b"
    println(text.isEmpty())                       // false
    println(Shell("").isEmpty())                   // true

    // A handle item is read through: the list holds the *pointee*.
    val borrowed: Str = "item"
    val ptr: *Str = *borrowed
    val boxed: &Str = &borrowed
    println(mirror(ptr, boxed))                   // [item::item]

    // The same for the other two packs: a list literal, and a call's trailing arguments.
    val parts: List<Str> = listOf<Str>(ptr, boxed, "leaf")
    println(parts.size().toString())              // 3
    println(parts[0])                             // item
    println(parcel("head", ptr, boxed, "leaf"))   // head+item+item+leaf
    return 0
}

// The `this` receiver shape: a prelude body called on a field of the receiver.
data class Shell(var text: Str) {

    fun isEmpty(): Bool {
        return this.text.isEmpty()
    }
}

// ---- shapes ----
enum class Shape {
    Circle,
    Square = 4
}

fun Str.describeShapes(): Str {
    return this
}

fun area(width: Int, height: Int): Int {
    return width * height
}

fun partShapes(): Int {
    val name: Str = "box"
    println(name.describeShapes())
    println(area(3, 4))
    println(Shape.Circle == Shape.Circle)
    println('a')
    return 0
}

// ---- xml-tree ----
// T15: builds, by hand, the XmlNode AST that the C++ converter
// (ast::toXmlNode) produces for `data class Tile(var n: Int)`
// (tests/fixtures/xml_probe.kt), then dumps it in the canonical XmlNode
// format. The expected stdout is the same tree the C++ converter emits, so a
// passing end-to-end run proves the Simse language can carry and manipulate the
// AST in XmlNode form.

fun attr(name: Str, value: Str): Attribute {
    return Attribute(name, value)
}

fun attrs3(a: Attribute, b: Attribute, c: Attribute): List<Attribute> {
    var list: List<Attribute> = List<Attribute>()
    list.append(a)
    list.append(b)
    list.append(c)
    return list
}

fun attrs4(a: Attribute, b: Attribute, c: Attribute, d: Attribute): List<Attribute> {
    var list: List<Attribute> = List<Attribute>()
    list.append(a)
    list.append(b)
    list.append(c)
    list.append(d)
    return list
}

// `Children` is an `Array<XmlNode>` (specs/xml-node.md): the two builders below
// are the language-level way to make one - an empty array is the shared
// zero-length block, and an array with elements is frozen from a list.
fun noChildren(): Array<XmlNode> {
    return Array<XmlNode>()
}

fun oneChild(child: XmlNode): Array<XmlNode> {
    var children: List<XmlNode> = List<XmlNode>()
    children.append(child)
    return children.toArray()
}

fun escapeText(text: Str): Str {
    var out: Str = Str()
    var i: Int = 0
    while (i < text.size()) {
        val ch: Char = text[i]
        if (ch == '\\') {
            out.append('\\')
            out.append('\\')
        } else if (ch == '\n') {
            out.append('\\')
            out.append('n')
        } else if (ch == '\r') {
            out.append('\\')
            out.append('r')
        } else if (ch == '\t') {
            out.append('\\')
            out.append('t')
        } else if (ch == '\'') {
            out.append('\\')
            out.append('\'')
        } else {
            out.append(ch)
        }
        i = i + 1
    }
    return out
}

fun indentation(depth: Int): Str {
    var out: Str = Str()
    var i: Int = 0
    while (i < depth * 2) {
        out.append(' ')
        i = i + 1
    }
    return out
}

fun dumpNode(node: XmlNode, depth: Int): Str {
    var out: Str = indentation(depth) + node.name
    var i: Int = 0
    while (i < node.attributes.size()) {
        val attribute: Attribute = node.attributes[i]
        out = out + " " + attribute.name + "='" + escapeText(attribute.value) + "'"
        i = i + 1
    }
    out = out + "\n"

    val children: Array<XmlNode> = node.Children
    var j: Int = 0
    while (j < children.count()) {
        out = out + dumpNode(children[j], depth + 1)
        j = j + 1
    }
    return out
}

fun partXmlTree(): Int {
    val typeNode: XmlNode = XmlNode("Type", attrs4(attr("kind", "Type.Named"), attr("line", "1"), attr("column", "24"), attr("name", "Int")), noChildren())
    val fieldNode: XmlNode = XmlNode("Field", attrs4(attr("name", "n"), attr("isVar", "true"), attr("line", "1"), attr("column", "17")), oneChild(typeNode))
    val dataClassNode: XmlNode = XmlNode("DataClass", attrs4(attr("kind", "DataClass"), attr("line", "1"), attr("column", "1"), attr("name", "Tile")), oneChild(fieldNode))
    val moduleNode: XmlNode = XmlNode("Module", attrs3(attr("kind", "Module"), attr("line", "1"), attr("column", "1")), oneChild(dataClassNode))

    print(dumpNode(moduleNode, 0))
    return 0
}

// ---- the category's entry ----
fun main(): Int {
    partCountedReference()
    partGenerics()
    partHello()
    partLambdas()
    partLanguageTour()
    partOptionalResult()
    partPointerPlace()
    partReceiverShapes()
    partRtlSimse()
    partShapes()
    partXmlTree()
    return 0
}
