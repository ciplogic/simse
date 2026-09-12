// Scratch stress test for SmallVector (not part of the build): exercises the
// inline/heap transitions, copy/move, erase/insert/resize, nested containers.
#include "cppsrc/rtl/containers.hpp"

#include <algorithm>
#include <cstdio>
#include <string>

static int failures = 0;

static void check(bool condition, const char *what) {
    if (!condition) {
        std::printf("FAIL %s\n", what);
        failures++;
    }
}

template <class T>
static void checkRange(const List<T> &list, int from, int count, const char *what) {
    check(list.size() == count, what);
    for (int i = 0; i < count; i++) {
        if (!(list[i] == T(from + i))) {
            std::printf("FAIL %s: element %d\n", what, i);
            failures++;
            return;
        }
    }
}

int main() {
    // Inline round trip: 4 elements stay inline, the 5th spills.
    {
        List<int> list;
        check(list.capacity() == 4, "empty capacity is the inline capacity");
        for (int i = 0; i < 4; i++) list.push_back(i);
        checkRange(list, 0, 4, "inline push_back");
        list.push_back(4);
        check(list.capacity() > 4, "spilled past the inline capacity");
        checkRange(list, 0, 5, "spilled push_back");
        list.pop_back();
        checkRange(list, 0, 4, "pop_back");
        list.clear();
        check(list.empty() && list.size() == 0, "clear");
    }

    // Copy, move, self-assign, init-list.
    {
        List<Str> list = {"a", "b", "c", "d", "e"};
        List<Str> copy = list;
        check(copy.size() == 5 && copy[4] == "e", "copy");
        List<Str> moved = std::move(copy);
        check(moved.size() == 5 && moved[0] == "a", "move ctor");
        check(copy.empty(), "moved-from is empty");
        List<Str> assigned;
        assigned = moved;
        check(assigned.size() == 5 && assigned[4] == "e", "copy assign");
        assigned = std::move(moved);
        check(assigned.size() == 5 && moved.empty(), "move assign");
        assigned = assigned;
        check(assigned.size() == 5, "self copy assign");
        List<Str> shortList = {"x"};
        List<Str> longList;
        for (int i = 0; i < 20; i++) longList.push_back(std::to_string(i));
        shortList = longList;               // inline <- spilled
        check(shortList.size() == 20 && shortList[19] == "19", "copy spilled into smaller");
        longList = List<Str>{"only"};       // spilled <- inline
        check(longList.size() == 1 && longList[0] == "only", "move assign inline");
    }

    // erase / insert / resize, across the boundary.
    {
        List<int> list;
        for (int i = 0; i < 10; i++) list.push_back(i);
        list.erase(list.begin() + 2);
        check(list.size() == 9 && list[2] == 3, "erase one");
        list.erase(list.begin(), list.begin() + 3);
        check(list.size() == 6 && list[0] == 4 && list[5] == 9, "erase range");
        list.insert(list.begin() + 1, 99);
        check(list.size() == 7 && list[1] == 99 && list[2] == 5, "insert middle");
        list.insert(list.end(), 77);
        check(list.size() == 8 && list[7] == 77, "insert at end");
        list.resize(10, -1);
        check(list.size() == 10 && list[8] == -1 && list[9] == -1, "resize up");
        list.resize(2);
        check(list.size() == 2 && list[0] == 4 && list[1] == 99, "resize down");
        check(list.begin() + (int) list.size() == list.end(), "end() tracks size");
    }

    // Nested small vectors inside a small vector, plus std::sort.
    {
        List<List<int>> grid;
        for (int i = 0; i < 6; i++) {
            List<int> row;
            for (int j = 0; j < 3; j++) row.push_back(i * 3 + j);
            grid.push_back(row);
        }
        check(grid.size() == 6 && grid[5][2] == 17, "nested vector");
        List<int> flat = grid[2];
        std::sort(flat.begin(), flat.end(), [](int a, int b) { return a > b; });
        check(flat[0] == 8, "std::sort over the iterators");
        List<int> sorted = {5, 4, 3, 2, 1, 0};
        std::sort(sorted.begin(), sorted.end());
        check(sorted[0] == 0 && sorted[5] == 5, "std::sort spilled");
    }

    // swap in all three representations.
    {
        List<int> inlineList = {1, 2};
        List<int> spilled;
        for (int i = 0; i < 9; i++) spilled.push_back(i);
        swap(inlineList, spilled);
        check(inlineList.size() == 9 && spilled.size() == 2 && spilled[1] == 2, "swap inline/heap");
        swap(inlineList, spilled);
        check(inlineList.size() == 2 && spilled.size() == 9, "swap back");
        List<int> a = {7, 8, 9, 10, 11};
        List<int> b = {1, 2, 3, 4, 5, 6};
        a.swap(b);
        check(a.size() == 6 && b.size() == 5 && a[5] == 6 && b[4] == 11, "swap heap/heap");
    }

    // at() bounds behaviour is abort-only, so only the in-range path is checked.
    {
        List<int> list = {1, 2, 3};
        check(list.at(1) == 2 && list.front() == 1 && list.back() == 3, "at/front/back");
        check(list.data()[0] == 1, "data");
        check(list == List<int>({1, 2, 3}), "operator==");
        check(list != List<int>({1, 2}), "operator!=");
    }

    std::printf(failures == 0 ? "smallvector: all checks passed\n" : "smallvector: %d failure(s)\n", failures);
    return failures == 0 ? 0 : 1;
}
