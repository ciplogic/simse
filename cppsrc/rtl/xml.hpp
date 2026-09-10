#pragma once

#include <utility>

#include "containers.hpp"
#include "types.hpp"

// Attribute pairs an attribute/element name with a Str value
// (specs/xml-node.md).
struct Attribute {
    Str name;
    Str value;

    Attribute() = default;
    Attribute(Str n, Str v) : name(std::move(n)), value(std::move(v)) {}
};

// XmlNode is a recursive tree node used for parse trees and debug output
// (specs/xml-node.md). Children is a PList<XmlNode> (a counted reference to a
// List<XmlNode>): the node holds a handle to a shared child list instead of
// embedding children inline. Copying an XmlNode shares the Children list and
// deep-copies name/attributes. There is no inheritance in the language subset,
// so one composed node type serves every parse-tree kind.
struct XmlNode {
    Str name;
    List<Attribute> attributes;
    PList<XmlNode> Children;

    XmlNode() = default;
    XmlNode(Str n, List<Attribute> attrs, PList<XmlNode> children)
        : name(std::move(n)), attributes(std::move(attrs)), Children(std::move(children)) {}
};