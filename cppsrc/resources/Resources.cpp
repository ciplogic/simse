#include "Resources.h"

#include <algorithm>

// The resource files (`_res.md`, specs/resources.md): the C++ ring of
// cppsrc/resources/Resources.kt, statement for statement. The two rings must agree on
// every entry, because the entries become the program's string table.
namespace resources {
    bool isUnderline(const Str& line) {
        const Str text = simse_str_trim(line);
        if (text.empty()) return false;
        for (Int i = 0; i < (Int) text.size(); i++) {
            if (text[i] != '=') return false;
        }
        return true;
    }

    bool isFenceStart(const Str& line) {
        return simse_str_startsWith(simse_str_trim(line), Str("```"));
    }

    bool isFenceEnd(const Str& line) {
        return simse_str_trim(line) == "```";
    }

    Str qualifiedKey(const Str& section, const Str& key) {
        if (section.empty()) return key;
        return section + ":" + key;
    }

    Str unquote(const Str& text) {
        if (text.size() >= 2 && text[0] == '`' && text[text.size() - 1] == '`') {
            return simse_str_substr(text, 1, (Int) text.size() - 2);
        }
        return text;
    }

    List<ResourceEntry> parseText(const Str& text) {
        const List<Str> lines = simse_str_split(text, Str("\n"));
        List<ResourceEntry> entries;
        Str section;
        Int i = 0;
        while (i < (Int) lines.size()) {
            // A title is a line *underlined* by the line below it; both lines are spent,
            // so an underline is never read as an entry of its own.
            if (i + 1 < (Int) lines.size() && isUnderline(lines[i + 1])) {
                const Str title = simse_str_trim(lines[i]);
                if (!title.empty()) section = title;
                i += 2;
                continue;
            }
            const Str line = simse_str_trim(lines[i]);
            const Int colon = simse_str_find(line, Str(":"));
            if (colon <= 0) {
                i++;
                continue;
            }
            const Str key = qualifiedKey(section,
                                         simse_str_trim(simse_str_substr(line, 0, colon)));
            const Str rest = simse_str_trim(
                    simse_str_substr(line, colon + 1, (Int) line.size() - colon - 1));
            if (!rest.empty()) {
                ResourceEntry entry;
                entry.key = key;
                entry.value = unquote(rest);
                entries.push_back(entry);
                i++;
                continue;
            }

            // Nothing after the colon: the value is the fenced block that follows. Blank
            // lines between the key and its fence are the file's own layout, so they are
            // skipped - and a line that is *not* a fence leaves the value empty rather
            // than swallowing it (the scan resumes there).
            Str value;
            Int j = i + 1;
            while (j < (Int) lines.size() && simse_str_trim(lines[j]).empty()) j++;
            if (j < (Int) lines.size() && isFenceStart(lines[j])) {
                j++;
                while (j < (Int) lines.size()) {
                    if (isFenceEnd(lines[j])) {
                        j++;
                        break;
                    }
                    // A `\r` before the newline is not part of the line - the same rule
                    // the scanner applies - so a value does not depend on the file's line
                    // endings.
                    Str body = lines[j];
                    if (body.size() > 0 && body[body.size() - 1] == '\r') {
                        body = simse_str_substr(body, 0, (Int) body.size() - 1);
                    }
                    value += body;
                    value += "\n";
                    j++;
                }
                i = j;
            } else {
                i++;
            }
            ResourceEntry entry;
            entry.key = key;
            entry.value = value;
            entries.push_back(entry);
        }
        return entries;
    }

    List<ResourceEntry> dedup(const List<ResourceEntry>& entries) {
        Dictionary<Str, Str> last;
        for (const ResourceEntry& entry: entries) {
            last[entry.key] = entry.value;
        }
        List<ResourceEntry> out;
        Dictionary<Str, bool> seen;
        for (const ResourceEntry& entry: entries) {
            if (seen.count(entry.key) > 0) continue;
            seen[entry.key] = true;
            ResourceEntry kept;
            kept.key = entry.key;
            kept.value = last[entry.key];
            out.push_back(kept);
        }
        return out;
    }

    List<Str> resourceFiles(const List<Str>& moduleRoots) {
        List<Str> candidates;
        for (const Str& root: moduleRoots) {
            for (const Str& found: common::filesInDir(root, ".md")) {
                if (simse_str_endsWith(found, Str("_res.md"))) {
                    candidates.push_back(found);
                }
            }
        }
        List<Str> chosen;
        Dictionary<Str, bool> seen;
        for (const Str& candidate: candidates) {
            const Str canon = common::canonicalPath(candidate);
            if (seen.count(canon) > 0) continue;
            seen[canon] = true;
            chosen.push_back(candidate);
        }
        // After dedup the canonical keys are unique, so this sort is total and both
        // compiler rings produce the same sequence.
        std::sort(chosen.begin(), chosen.end(), [](const Str& left, const Str& right) {
            return common::canonicalPath(left) < common::canonicalPath(right);
        });
        return chosen;
    }

    List<ResourceEntry> loadFiles(const List<Str>& files) {
        List<ResourceEntry> all;
        for (const Str& file: files) {
            const List<ResourceEntry> parsed = parseText(common::readFile(file));
            for (const ResourceEntry& entry: parsed) {
                all.push_back(entry);
            }
        }
        return dedup(all);
    }

    List<ResourceEntry> load(const List<Str>& moduleRoots) {
        return loadFiles(resourceFiles(moduleRoots));
    }

    List<Str> loadLiterals(const List<Str>& moduleRoots) {
        const List<ResourceEntry> entries = load(moduleRoots);
        List<Str> out;
        for (const ResourceEntry& entry: entries) {
            out.push_back(quoteLiteral(entry.key));
            out.push_back(quoteLiteral(entry.value));
        }
        return out;
    }

    Str quoteLiteral(const Str& text) {
        Str out = "\"";
        for (Int i = 0; i < (Int) text.size(); i++) {
            const char ch = text[i];
            if (ch == '\\') {
                out += "\\\\";
            } else if (ch == '"') {
                out += "\\\"";
            } else if (ch == '\n') {
                out += "\\n";
            } else if (ch == '\r') {
                out += "\\r";
            } else if (ch == '\t') {
                out += "\\t";
            } else {
                out += ch;
            }
        }
        out += "\"";
        return out;
    }
}
