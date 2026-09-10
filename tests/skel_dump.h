#pragma once

//
// Shared canonical tree dump for the skeleton-parser differential drivers.
//
// Both `tests/skel_ref_main.cpp` (hand-written parseSkeleton) and
// `tests/skel_simse_main.cpp` (the transpiled one) convert their native node
// tree into `skeldump::Node` and render it here, so the two outputs cannot
// drift. The format is one line per node, two spaces of indent per depth:
//
//     <TypeName> <line>:<column> '<escapedText>'
//
// `TypeName` is `Program`, `Terminal`, or a block type (`Paren`, `Square`,
// `Generics`, `Block`); the position is the node's token position. Escaping is
// the same as the token goldens (`\`, `\n`, `\r`, `\t`).
//

#include <string>
#include <vector>

namespace skeldump {
    // A backend-independent snapshot of one SkeletonNode.
    struct Node {
        int typeOrdinal = 0; // SkeletonType enum ordinal
        int line = 0;
        int column = 0;
        std::string text;
        std::vector<Node> children;
    };

    inline std::string escapeText(const std::string &text) {
        std::string escaped;
        for (char ch: text) {
            switch (ch) {
                case '\\': escaped += "\\\\"; break;
                case '\n': escaped += "\\n"; break;
                case '\r': escaped += "\\r"; break;
                case '\t': escaped += "\\t"; break;
                default: escaped += ch; break;
            }
        }
        return escaped;
    }

    inline const char *typeName(int ordinal) {
        static const char *names[] = {
            "None", "Program", "Terminal", "Paren", "Square", "Generics", "Block",
        };
        if (ordinal < 0 || ordinal > 6) return "Unknown";
        return names[ordinal];
    }

    inline void dumpInto(const Node &node, int depth, std::string &out) {
        out.append((size_t) depth * 2, ' ');
        out += typeName(node.typeOrdinal);
        out += ' ';
        out += std::to_string(node.line);
        out += ':';
        out += std::to_string(node.column);
        out += " '";
        out += escapeText(node.text);
        out += "'\n";
        for (const Node &child: node.children) {
            dumpInto(child, depth + 1, out);
        }
    }

    inline std::string dump(const Node &root) {
        std::string out;
        dumpInto(root, 0, out);
        return out;
    }

    // The single-line marker both drivers emit when scanning fails.
    inline std::string errorLine(const std::string &message) {
        return "Error " + escapeText(message) + "\n";
    }
}
