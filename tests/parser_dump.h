#pragma once

//
// Shared canonical output for the parser differential drivers.
//
// Both `tests/parser_ref_main.cpp` (hand-written parser) and
// `tests/parser_simse_main.cpp` (the transpiled one) render their result here so
// the two outputs cannot drift. For each fixture the format is:
//
//     === <basename> ===
//     <indented XmlNode dump, from ast::dumpXmlNode>
//
// or, when the fixture does not scan/parse, a single marker line:
//
//     ScanError <escaped message>
//     ParseError <escaped message>
//
// Escaping is the same as the token goldens (`\`, `\n`, `\r`, `\t`).
//

#include <string>

namespace parserdump {
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

    inline std::string header(const std::string &name) {
        return "=== " + name + " ===\n";
    }

    inline std::string scanErrorLine(const std::string &message) {
        return "ScanError " + escapeText(message) + "\n";
    }

    inline std::string parseErrorLine(const std::string &message) {
        return "ParseError " + escapeText(message) + "\n";
    }
}
