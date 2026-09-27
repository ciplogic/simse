Generated C++
====
The `sockets` module's ABI glue - the declarations are `api.kt`. The socket calls themselves are
`@SmGen("native", "ws2_32.dll", ...)`; what is C++ here is only the platform's *structs* and the
one bulk copy at the boundary:

- `SOCKADDR_IN` and `WSADATA` - their size, their layout and the storage the platform writes into
  (`simse_net_addr`, `simse_net_addrSize`, `simse_net_fillAddr`, `simse_net_readPort`,
  `simse_net_wsaData`). Simse cannot lay a struct out at the platform's alignment nor reinterpret a
  pointer, and a scratch the platform writes through must outlive the call;
- the receive buffer and its capacity (`simse_net_buffer`, `simse_net_recvCap`) - the *caller* owns
  one (a heap `List<Int8>`), so two queues' threads can read at once and the library keeps no buffer
  of its own;
- `simse_net_bytesToStr` - the one bulk copy (`memcpy`) where native bytes become an owned `Str`.

Nothing else: an option value, a name length and a `Str`'s byte address are all ordinary Simse
here (`*reuse`, `*nameLength`, `spanOfStr(text).atPtr(offset)`), so the glue holds no logic - and
no Winsock function is called from it, which is why nothing has to be linked. `emit: reached`
gates the text for a module's declaration.

!netglue
====
emit: reached
includes:
```cpp
// winsock2.h for the ABI structs only. WIN32_LEAN_AND_MEAN is defined by the `native`
// generator's own include block, which renders before this one, so windows.h has not pulled in
// the old winsock.h.
#include <winsock2.h>
#include <cstring>
```
forward:
```cpp
Int8* simse_net_wsaData();
Int8* simse_net_addr();
Int32 simse_net_addrSize();
Int32 simse_net_fillAddr(Int8* addr, Int32 ip, Int32 port);
Int32 simse_net_readPort(const Int8* addr);
Str simse_net_bytesToStr(const Int8* bytes, Int32 count);
List<Int8> simse_net_buffer(Int32 size);
Int32 simse_net_recvCap();
```
bodies:
```cpp
// Host order <-> network order, written out rather than calling htons/ntohs: those live in
// ws2_32, and calling them here would mean linking the very library this module exists to avoid.
static inline std::uint32_t simse_net_swap32(std::uint32_t value) {
    return ((value & 0x000000ffu) << 24)
        | ((value & 0x0000ff00u) << 8)
        | ((value & 0x00ff0000u) >> 8)
        | ((value & 0xff000000u) >> 24);
}

static inline std::uint16_t simse_net_swap16(std::uint16_t value) {
    return (std::uint16_t) (((value & 0x00ffu) << 8) | ((value & 0xff00u) >> 8));
}

// One `WSADATA` the startup call writes into: the platform's own struct, which has to outlive the
// call and be aligned for its fields, so it is a static here rather than a Simse byte list.
Int8* simse_net_wsaData() {
    static WSADATA data;
    return reinterpret_cast<Int8*>(&data);
}

// One `SOCKADDR_IN` scratch, reused by bind, connect and getsockname: a caller fills it, uses it,
// and reads it back, never holding two at once.
Int8* simse_net_addr() {
    static SOCKADDR_IN name;
    return reinterpret_cast<Int8*>(&name);
}

Int32 simse_net_addrSize() {
    return (Int32) sizeof(SOCKADDR_IN);
}

// `ip` and `port` are host order; the struct is network order. `ip == 0` is INADDR_ANY. The
// result is the struct's size, which is what `bind`/`connect` want as the name length.
Int32 simse_net_fillAddr(Int8* addr, Int32 ip, Int32 port) {
    SOCKADDR_IN* name = reinterpret_cast<SOCKADDR_IN*>(addr);
    name->sin_family = AF_INET;
    name->sin_port = simse_net_swap16((std::uint16_t) (port & 0xffff));
    name->sin_addr.s_addr = simse_net_swap32((std::uint32_t) ip);
    std::memset(name->sin_zero, 0, sizeof(name->sin_zero));
    return (Int32) sizeof(SOCKADDR_IN);
}

Int32 simse_net_readPort(const Int8* addr) {
    const SOCKADDR_IN* name = reinterpret_cast<const SOCKADDR_IN*>(addr);
    return (Int32) simse_net_swap16(name->sin_port);
}

// The one copy at the boundary: the bytes a read wrote, as an owned `Str`.
Str simse_net_bytesToStr(const Int8* bytes, Int32 count) {
    Str text;
    if (count <= 0) {
        return text;
    }
    text.resize(count);
    std::memcpy(text.data(), bytes, (std::size_t) count);
    return text;
}

// The receive buffer a caller owns: a heap `List<Int8>` of `size` bytes, so two queue threads can
// each read a connection at once. The library keeps no buffer of its own.
List<Int8> simse_net_buffer(Int32 size) {
    List<Int8> buffer;
    buffer.resize(size);
    return buffer;
}

// The conventional read size a caller's `simse_net_buffer` uses: a request line and its headers fit
// comfortably, and a caller that needs more reads another chunk into the same buffer.
Int32 simse_net_recvCap() {
    return (Int32) 2048;
}
```
