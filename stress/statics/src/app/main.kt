package app

import store

// File-level static storage (specs/statics.md): the storage starts empty and the
// initializers run in one generated pass before the body of `main`. This case
// pins the file-level half of the feature - a `var`, a `val`, an initializer that
// is a call, declarations with no initializer at all, a function that reads a
// static declared after it, and a static another package declares.
//
// No initializer here reads another static: the relative order of two static
// initializers is unspecified (specs/statics.md), so a case that depended on it
// would be pinning a program the language does not promise anything about.

var requests: Int = 0
val origin: Str = "boot"
var computed: Int = seed() + 1

// A static whose initializer builds an aggregate (through the emitted factory)
// and one whose initializer allocates: both run in the pass, and both storage
// shapes start empty first.
data class Point(var x: Int, var y: Int) {
    fun describe(): Str {
        return this.x.toString() + "," + this.y.toString()
    }
}

var home: Point = Point(1, 2)
var names: List<Str> = List<Str>()

// Left without an initializer: the storage stays as it started - the empty value
// for the type, never indeterminate data.
var label: Str
var unset: Int

fun seed(): Int {
    return 40
}

// Hoisting covers statics too: the name is visible before its declaration.
var later: Int = 21

fun doubled(): Int {
    return later * 2
}

fun bump(): Unit {
    requests = requests + 1
}

fun main(): Int {
    println(origin)
    println(requests)
    bump()
    bump()
    println(requests)
    println(computed)
    println(label.isEmpty())
    println(unset)
    println(doubled())
    println(home.describe())
    names.append("first")
    println(names.size())
    println(names[0])
    // `hits` and `report` come from the imported package: statics are reached by
    // their simple name, like every other imported declaration.
    println(hits)
    hits = hits + 5
    println(report())
    println(doubled() + hits)
    return 0
}
