package fixtures

// The xml module's parser and writer (modules/xml/parse.kt, modules/xml/write.kt): a
// document round-trips through parse, write, parse, write - the second write is the
// first - and every failure the grammar rejects comes back as a positioned `Res` error,
// not a crash. The query helpers (query.kt) read the tree back by name.

import xml

fun main(): Int {
    // A parse, read back through the helpers: attributes by name, text by child, the
    // element walk.
    val catalog: Res<XmlNode> = xmlParse(`<catalog version="2"><book id="b1">Kotlin &amp; XML</book><book id="b2" lang="en"><title>Nested &lt;Things&gt;</title><meta/></book></catalog>`)
    println("parse ok: " + catalog.isOk().toString())
    if (!catalog.isOk()) {
        return 1
    }
    println("version: " + catalog.value().attributeOr("version", "missing"))
    val book1: Opt<XmlNode> = catalog.value().child("book")
    println("first book: " + book1.value().textOf())
    println("books named book: " + catalog.value().collect("book").size().toString())
    val kids: List<XmlNode> = catalog.value().elementList()
    var names: Str = Str()
    for (kid in kids) {
        names.appendStr(kid.name + " ")
    }
    println("elements: " + names)

    // The round trip: write, parse again, write - byte-stable, entities normalized the
    // same way both times.
    val once: Str = xmlWrite(catalog.value())
    val again: Res<XmlNode> = xmlParse(once)
    val stable: Bool = again.isOk() && xmlWrite(again.value()) == once
    println("round trip stable: " + stable.toString())

    // CDATA becomes a text child; comments and declarations do not survive a parse.
    val misc: Res<XmlNode> = xmlParse(`<?xml version="1.0"?><!--note--><d><![CDATA[if (a<b) {x();}]]></d>`)
    println("cdata: [" + misc.value().textOf() + "]")

    // Character references: decimal and hex.
    val refs: Res<XmlNode> = xmlParse(`<r t='&#65;&#x42;'>&#67;</r>`)
    println("refs: " + refs.value().attributeOr("t", "") + refs.value().textOf())

    // The failures: unclosed, mismatched, unknown entity, unquoted value, stray content.
    println("unclosed: " + xmlParse(`<a><b></a>`).error())
    println("mismatch: " + xmlParse(`<a></b>`).error())
    println("bad entity: " + xmlParse(`<a>&nope;</a>`).error())
    println("no quote: " + xmlParse(`<a b=c/>`).error())
    println("after root: " + xmlParse(`<a/>trailing`).error())
    return 0
}
