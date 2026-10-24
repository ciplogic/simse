Generated C++
====
The probes: a scalar, a bool and a container, the shapes a `res` binding returns.

!probecall
====
emit: reached
forward:
```cpp
Int64 simse_probe_int();
Bool simse_probeFlag();
List<Int> simse_probe_list();
```
bodies:
```cpp
Int64 simse_probe_int() { return 42; }
Bool simse_probeFlag() { return true; }
List<Int> simse_probe_list() {
    List<Int> out;
    out.push_back(3);
    out.push_back(4);
    out.push_back(5);
    return out;
}
```
