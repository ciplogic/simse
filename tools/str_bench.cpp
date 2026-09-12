// Scratch benchmark: Str operation cost under the two backings (not part of the
// build). Compile with and without SIMSE_STR_STD_STRING.
#include "cppsrc/rtl/simse.hpp"

#include <chrono>
#include <cstdio>

namespace {
    template <class F>
    void bench(const char* name, F body) {
        auto begin = std::chrono::steady_clock::now();
        long long checksum = body();
        auto end = std::chrono::steady_clock::now();
        double ms = std::chrono::duration<double, std::milli>(end - begin).count();
        std::printf("%-22s %9.1f ms   (sum %lld)\n", name, ms, checksum);
    }

    inline Str benchTakeName(Str name) { return name; }

    // Consumes the bytes, so the copies below cannot be eliminated as dead
    // stores: a benchmark that only reads `size()` measures the optimizer, not
    // the container.
    inline long long digest(const Str& value) {
        if (value.size() == 0) return 0;
        return (long long) value.size() + (unsigned char) value[0]
               + (unsigned char) value[value.size() - 1];
    }
}

int main() {
    const int n = 4000000;
    const char* shortText = "Expr.GenericName";
    const char* longText = "the parser produced a very long attribute value that spills out of any inline buffer";

    bench("construct short", [&] {
        long long sum = 0;
        for (int i = 0; i < n; i++) {
            Str value(shortText);
            sum += digest(value);
        }
        return sum;
    });

    bench("construct long", [&] {
        long long sum = 0;
        for (int i = 0; i < n; i++) {
            Str value(longText);
            sum += digest(value);
        }
        return sum;
    });

    bench("copy ctor short", [&] {
        Str source(shortText);
        long long sum = 0;
        for (int i = 0; i < n; i++) {
            Str value(source);
            sum += digest(value);
        }
        return sum;
    });

    bench("copy assign short", [&] {
        Str source(shortText);
        Str value;
        long long sum = 0;
        for (int i = 0; i < n; i++) {
            value = source;
            sum += digest(value);
        }
        return sum;
    });

    bench("compare short", [&] {
        Str left(shortText);
        Str right(shortText);
        long long sum = 0;
        for (int i = 0; i < n; i++) {
            if (left == right) sum++;
        }
        return sum;
    });

    bench("compare vs literal", [&] {
        Str value("Expr.GenericName");
        long long sum = 0;
        for (int i = 0; i < n; i++) {
            if (value == "Expr.GenericName") sum++;
        }
        return sum;
    });

    bench("ctor vs literal", [&] {
        long long sum = 0;
        for (int i = 0; i < n; i++) {
            Str value = "Expr.GenericName";
            sum += digest(value);
        }
        return sum;
    });

    bench("default ctor", [&] {
        long long sum = 0;
        for (int i = 0; i < n; i++) {
            Str value;
            sum += digest(value);
        }
        return sum;
    });

    bench("ctor ptr+count", [&] {
        long long sum = 0;
        for (int i = 0; i < n; i++) {
            Str value(shortText, 16);
            sum += digest(value);
        }
        return sum;
    });

    bench("build by push_back", [&] {
        long long sum = 0;
        for (int i = 0; i < n; i++) {
            Str value;
            for (int j = 0; j < 8; j++) value.push_back((char) ('a' + j));
            sum += digest(value);
        }
        return sum;
    });

    bench("append string", [&] {
        long long sum = 0;
        for (int i = 0; i < n; i++) {
            Str value;
            value.append("abcd");
            sum += digest(value);
        }
        return sum;
    });

    // The `xmlAttr(node, "name")` shape: a literal crossing a by-value Str
    // parameter.
    bench("arg vs literal", [&] {
        long long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += digest(benchTakeName("Expr.GenericName"));
        }
        return sum;
    });

    bench("concat short+short", [&] {
        Str left("Expr.");
        Str right("GenericName");
        long long sum = 0;
        for (int i = 0; i < n; i++) {
            Str joined = left + right;
            sum += (long long) joined.size();
        }
        return sum;
    });

    bench("substr mid", [&] {
        Str source(longText);
        long long sum = 0;
        for (int i = 0; i < n; i++) {
            Str part = source.substr(10, 12);
            sum += (long long) part.size();
        }
        return sum;
    });

    bench("find in long", [&] {
        Str source(longText);
        Str needle("spills");
        long long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += (long long) source.find(needle);
        }
        return sum;
    });

    bench("dict insert/lookup", [&] {
        Dictionary<Str, Int> table;
        long long sum = 0;
        for (int i = 0; i < n; i++) {
            Str key = Str("key_") + Str(simse_int_toString(i % 64));
            table[key] = (Int) i;
            Opt<Int> found = simse_dict_get(table, key);
            if (found.hasValue()) sum += found.value();
        }
        return sum;
    });

    // ---- List<T> backing (SmallVector vs SIMSE_LIST_STD_VECTOR) -----------
    bench("List<int> push 4", [&] {
        long long sum = 0;
        for (int i = 0; i < n; i++) {
            List<int> values;
            for (int j = 0; j < 4; j++) values.push_back(j);
            sum += values[3];
        }
        return sum;
    });

    bench("List<int> push 64", [&] {
        long long sum = 0;
        for (int i = 0; i < n / 10; i++) {
            List<int> values;
            for (int j = 0; j < 64; j++) values.push_back(j);
            sum += values[63];
        }
        return sum;
    });

    bench("List<int> copy 4", [&] {
        List<int> source;
        for (int j = 0; j < 4; j++) source.push_back(j);
        long long sum = 0;
        for (int i = 0; i < n; i++) {
            List<int> values(source);
            sum += values[0] + values[3];
        }
        return sum;
    });

    bench("List<Str> push 8", [&] {
        long long sum = 0;
        for (int i = 0; i < n / 10; i++) {
            List<Str> names;
            names.push_back("alpha");
            names.push_back("beta");
            names.push_back("gamma");
            names.push_back("delta");
            names.push_back("epsilon");
            names.push_back("zeta");
            names.push_back("eta");
            names.push_back("theta");
            sum += (long long) names[7].size() + names[0][0];
        }
        return sum;
    });

    bench("list of xmlnodes", [&] {
        long long sum = 0;
        for (int i = 0; i < n / 100; i++) {
            List<Attribute> attributes;
            attributes.push_back(Attribute("kind", "Expr.GenericName"));
            attributes.push_back(Attribute("line", "42"));
            attributes.push_back(Attribute("column", "7"));
            XmlNode node("Expr", attributes, makeList<XmlNode>());
            XmlNode copy = node;
            sum += (long long) copy.attributes.size() + (long long) copy.name.size();
        }
        return sum;
    });

    std::printf("sizeof(Str) = %zu\n", sizeof(Str));
#ifdef SIMSE_STR_STD_STRING
    std::printf("backing: std::string\n");
#else
    std::printf("backing: SmString\n");
#endif
    return 0;
}
