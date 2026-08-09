# HuffmanByteCodec 查表设计

本文档描述 `HuffmanByteCodec` 中所有静态查表的生成逻辑与结构。  
数据来源为 RFC 7541 Appendix B 定义的 HPACK Huffman 编码表。

---

## 1. MASKS — 位掩码表

```
MASKS[n] = (1 << n) - 1
```

| n    | 0  | 1   | 2   | 3   | 4   | 5    | 6    | 7    | 8    | 9     | 10    | 11    | 12    | 13     | 14     | 15     | 16     |
|------|----|-----|-----|-----|-----|------|------|------|------|-------|-------|-------|-------|--------|--------|--------|--------|
| 值   | 0  | 0x1 | 0x3 | 0x7 | 0xF | 0x1F | 0x3F | 0x7F | 0xFF | 0x1FF | 0x3FF | 0x7FF | 0xFFF | 0x1FFF | 0x3FFF | 0x7FFF | 0xFFFF |

用途：提取低 n 位，如 `octet & MASKS[remBits]` 获取剩余位。

---

## 2. ENCODE_VALUES / ENCODE_BITS — 编码表

直接静态数组，按 RFC 7541 Appendix B 的原始十六进制值和位长硬编码，索引 0~255 对应字节值。

- `ENCODE_VALUES[byte]` — 该字节的 Huffman 编码值（LSB 对齐）
- `ENCODE_BITS[byte]` — 该字节的 Huffman 编码位长（5~30 bit）

编码公式：
```
ev = ev << n | ENCODE_VALUES[idx]
bits += ENCODE_BITS[idx]
```

### ENCODE_VALUES 完整数据

```java
private static final int[] ENCODE_VALUES = {
        /*   0 */ 0x1FF8,     0x7FFFD8,    0xFFFFFE2,   0xFFFFFE3,   0xFFFFFE4,   0xFFFFFE5,   0xFFFFFE6,   0xFFFFFE7,   0xFFFFFE8,   0xFFFFEA,    0x3FFFFFFC,  0xFFFFFE9,   0xFFFFFEA,   0x3FFFFFFD,  0xFFFFFEB,   0xFFFFFEC,
        /*  16 */ 0xFFFFFED,  0xFFFFFEE,   0xFFFFFEF,   0xFFFFFF0,   0xFFFFFF1,   0xFFFFFF2,   0x3FFFFFFE,  0xFFFFFF3,   0xFFFFFF4,   0xFFFFFF5,   0xFFFFFF6,   0xFFFFFF7,   0xFFFFFF8,   0xFFFFFF9,   0xFFFFFFA,   0xFFFFFFB,
        /*  32 */ 0x14,       0x3F8,       0x3F9,       0xFFA,       0x1FF9,      0x15,        0xF8,        0x7FA,       0x3FA,       0x3FB,       0xF9,        0x7FB,       0xFA,        0x16,        0x17,        0x18,
        /*  48 */ 0x0,        0x1,         0x2,         0x19,        0x1A,        0x1B,        0x1C,        0x1D,        0x1E,        0x1F,        0x5C,        0xFB,        0x7FFC,      0x20,        0xFFB,       0x3FC,
        /*  64 */ 0x1FFA,     0x21,        0x5D,        0x5E,        0x5F,        0x60,        0x61,        0x62,        0x63,        0x64,        0x65,        0x66,        0x67,        0x68,        0x69,        0x6A,
        /*  80 */ 0x6B,       0x6C,        0x6D,        0x6E,        0x6F,        0x70,        0x71,        0x72,        0xFC,        0x73,        0xFD,        0x1FFB,      0x7FFF0,     0x1FFC,      0x3FFC,      0x22,
        /*  96 */ 0x7FFD,     0x3,         0x23,        0x4,         0x24,        0x5,         0x25,        0x26,        0x27,        0x6,         0x74,        0x75,        0x28,        0x29,        0x2A,        0x7,
        /* 112 */ 0x2B,       0x76,        0x2C,        0x8,         0x9,         0x2D,        0x77,        0x78,        0x79,        0x7A,        0x7B,        0x7FFE,      0x7FC,       0x3FFD,      0x1FFD,      0xFFFFFFC,
        /* 128 */ 0xFFFE6,    0x3FFFD2,    0xFFFE7,     0xFFFE8,     0x3FFFD3,    0x3FFFD4,    0x3FFFD5,    0x7FFFD9,    0x3FFFD6,    0x7FFFDA,    0x7FFFDB,    0x7FFFDC,    0x7FFFDD,    0x7FFFDE,    0xFFFFEB,    0x7FFFDF,
        /* 144 */ 0xFFFFEC,   0xFFFFED,    0x3FFFD7,    0x7FFFE0,    0xFFFFEE,    0x7FFFE1,    0x7FFFE2,    0x7FFFE3,    0x7FFFE4,    0x1FFFDC,    0x3FFFD8,    0x7FFFE5,    0x3FFFD9,    0x7FFFE6,    0x7FFFE7,    0xFFFFEF,
        /* 160 */ 0x3FFFDA,   0x1FFFDD,    0xFFFE9,     0x3FFFDB,    0x3FFFDC,    0x7FFFE8,    0x7FFFE9,    0x1FFFDE,    0x7FFFEA,    0x3FFFDD,    0x3FFFDE,    0xFFFFF0,    0x1FFFDF,    0x3FFFDF,    0x7FFFEB,    0x7FFFEC,
        /* 176 */ 0x1FFFE0,   0x1FFFE1,    0x3FFFE0,    0x1FFFE2,    0x7FFFED,    0x3FFFE1,    0x7FFFEE,    0x7FFFEF,    0xFFFEA,     0x3FFFE2,    0x3FFFE3,    0x3FFFE4,    0x7FFFF0,    0x3FFFE5,    0x3FFFE6,    0x7FFFF1,
        /* 192 */ 0x3FFFFE0,  0x3FFFFE1,   0xFFFEB,     0x7FFF1,     0x3FFFE7,    0x7FFFF2,    0x3FFFE8,    0x1FFFFEC,   0x3FFFFE2,   0x3FFFFE3,   0x3FFFFE4,   0x7FFFFDE,   0x7FFFFDF,   0x3FFFFE5,   0xFFFFF1,    0x1FFFFED,
        /* 208 */ 0x7FFF2,    0x1FFFE3,    0x3FFFFE6,   0x7FFFFE0,   0x7FFFFE1,   0x3FFFFE7,   0x7FFFFE2,   0xFFFFF2,    0x1FFFE4,    0x1FFFE5,    0x3FFFFE8,   0x3FFFFE9,   0xFFFFFFD,   0x7FFFFE3,   0x7FFFFE4,   0x7FFFFE5,
        /* 224 */ 0xFFFEC,    0xFFFFF3,    0xFFFED,     0x1FFFE6,    0x3FFFE9,    0x1FFFE7,    0x1FFFE8,    0x7FFFF3,    0x3FFFEA,    0x3FFFEB,    0x1FFFFEE,   0x1FFFFEF,   0xFFFFF4,    0xFFFFF5,    0x3FFFFEA,   0x7FFFF4,
        /* 240 */ 0x3FFFFEB,  0x7FFFFE6,   0x3FFFFEC,   0x3FFFFED,   0x7FFFFE7,   0x7FFFFE8,   0x7FFFFE9,   0x7FFFFEA,   0x7FFFFEB,   0xFFFFFFE,   0x7FFFFEC,   0x7FFFFED,   0x7FFFFEE,   0x7FFFFEF,   0x7FFFFF0,   0x3FFFFEE
};
```

### ENCODE_BITS 完整数据

```java
private static final byte[] ENCODE_BITS = {
        /*  0 */ 13, 23, 28, 28, 28, 28, 28, 28, 28, 24, 30, 28, 28, 30, 28, 28,
        /* 16 */ 28, 28, 28, 28, 28, 28, 30, 28, 28, 28, 28, 28, 28, 28, 28, 28,
        /* 32 */  6, 10, 10, 12, 13,  6,  8, 11, 10, 10,  8, 11,  8,  6,  6,  6,
        /* 48 */  5,  5,  5,  6,  6,  6,  6,  6,  6,  6,  7,  8, 15,  6, 12, 10,
        /* 64 */ 13,  6,  7,  7,  7,  7,  7,  7,  7,  7,  7,  7,  7,  7,  7,  7,
        /* 80 */  7,  7,  7,  7,  7,  7,  7,  7,  8,  7,  8, 13, 19, 13, 14,  6,
        /* 96 */ 15,  5,  6,  5,  6,  5,  6,  6,  6,  5,  7,  7,  6,  6,  6,  5,
        /*112 */  6,  7,  6,  5,  5,  6,  7,  7,  7,  7,  7, 15, 11, 14, 13, 28,
        /*128 */ 20, 22, 20, 20, 22, 22, 22, 23, 22, 23, 23, 23, 23, 23, 24, 23,
        /*144 */ 24, 24, 22, 23, 24, 23, 23, 23, 23, 21, 22, 23, 22, 23, 23, 24,
        /*160 */ 22, 21, 20, 22, 22, 23, 23, 21, 23, 22, 22, 24, 21, 22, 23, 23,
        /*176 */ 21, 21, 22, 21, 23, 22, 23, 23, 20, 22, 22, 22, 23, 22, 22, 23,
        /*192 */ 26, 26, 20, 19, 22, 23, 22, 25, 26, 26, 26, 27, 27, 26, 24, 25,
        /*208 */ 19, 21, 26, 27, 27, 26, 27, 24, 21, 21, 26, 26, 28, 27, 27, 27,
        /*224 */ 20, 24, 20, 21, 22, 21, 21, 23, 22, 22, 25, 25, 24, 24, 26, 23,
        /*240 */ 26, 27, 26, 26, 27, 27, 27, 27, 27, 28, 27, 27, 27, 27, 27, 26,
};
```

---

## 3. DECODE_VALUES / DECODE_BITS / DECODE_MANTISSAS — 一级解码表

三个数组，大小 256，由 `static {}` 块动态生成。

### 生成逻辑

```java
static void set(int i, byte val, int bits, int mantissa) {
    DECODE_VALUES[i]    = val;       // 解码出的字节值
    DECODE_BITS[i]      = (byte) bits;  // 编码位长
    DECODE_MANTISSAS[i] = (byte) mantissa;  // 编码值去掉高位后的尾数
}
```

按位长分组、按顺序填入：

| 位长 | 索引范围    | 字符集（按顺序）                                                                 | 每字符条目数 | 总条目 |
|------|------------|----------------------------------------------------------------------------------|-------------|--------|
| 5    | 0 ~ 79     | `'0','1','2','a','c','e','i','o','s','t'`                                        | 8           | 80     |
| 6    | 80 ~ 183   | `' ','%','-','.','/','3','4','5','6','7','8','9','=','A','_','b','d','f','g','h','l','m','n','p','r','u'` | 4           | 104    |
| 7    | 184 ~ 247  | `':','B','C','D','E','F','G','H','I','J','K','L','M','N','O','P','Q','R','S','T','U','V','W','Y','j','k','q','v','w','x','y','z'` | 2           | 64     |
| 8    | 248 ~ 253  | `'&','*',',',';','X','Z'`                                                        | 1           | 6      |
| —    | 254        | 特殊：10-bit 多字节入口，`DECODE_BITS[254] = 10`                                  | —           | 1      |
| —    | 255        | 特殊：11~30-bit 多字节入口，`DECODE_BITS[255] = 32`                               | —           | 1      |

### 索引含义

解码器读取一个字节 `b`，以 `b & 0xFF` 作为索引查一级表：

- `DECODE_BITS[idx] <= 8`：直接解码出 `DECODE_VALUES[idx]`
- `idx == 254`：进入二级表（idx=254 路径，10-bit 码）
- `idx == 255`：进入二级表（idx=255 路径，11~30-bit 码）

### mantissa 含义

`mantissa` 是编码值去掉高位前缀后的低位部分，用于跨字节边界拼接下一个字符。

```
mantissa = DECODE_MANTISSAS[idx] << nextRemBits | nextMantissa
```

---

## 4. FF_LEVEL0 — Level-0 二级查表（idx=255 路径）

`short[256]` 数组，由 `static {}` 块动态生成，代码为简化已内联为静态初始化。

### 生成逻辑

```java
int idx = 0;
// bits10: follow(0-63), 高2位为00
for (int i = 0; i < 64; ++i) FF_LEVEL0[idx++] = (short) (0x600 | '?');

// bits11: follow(64-159)
byte[] bits11 = {'\'', '+', '|'};
for (byte b : bits11)
    for (int i = 0; i < 32; ++i)
        FF_LEVEL0[idx++] = (short) (0x500 | b);  // 0x500 = 5 << 8

// bits12: follow(160-191)
byte[] bits12 = {'#', '>'};
for (byte b : bits12)
    for (int i = 0; i < 16; ++i)
        FF_LEVEL0[idx++] = (short) (0x400 | b);  // 0x400 = 4 << 8

// bits13: follow(192-239)
byte[] bits13 = {0, '$', '@', '[', ']', '~'};
for (byte b : bits13)
    for (int i = 0; i < 8; ++i)
        FF_LEVEL0[idx++] = (short) (0x300 | b);  // 0x300 = 3 << 8

// bits14: follow(240-247)
byte[] bits14 = {'^', '}'};
for (byte b : bits14)
    for (int i = 0; i < 4; ++i)
        FF_LEVEL0[idx++] = (short) (0x200 | b);  // 0x200 = 2 << 8

// bits15: follow(248-253)
byte[] bits15 = {'<', '`', '{'};
for (byte b : bits15)
    for (int i = 0; i < 2; ++i)
        FF_LEVEL0[idx++] = (short) (0x100 | b);  // 0x100 = 1 << 8

// 254-255: 0 (继续到 level 1+)
```

### 用途

当一级表 `idx == 255` 时，进入多字节解码循环。每次读取一个后续字节 `octet`，计算 `follow = (nextMantissa << 8 | octet) >> nextRemBits`，以 `follow` 作为 `FF_LEVEL0` 的下标。

### 条目格式

```
FF_LEVEL0[follow] = (consumedBits << 8) | decodedByte
```

- `consumedBits`：该次解码消耗的额外位长（不含第一个字节的 8 bit）
- `decodedByte`：解码出的字节值
- `0`：表示 follow 为 254/255，需继续查更深层表

### follow 范围分布（0-253 连续覆盖）

| follow 范围     | 总位长 | 额外位长 | 前缀（2字节）                   | 解码字符                   | 条目数 |
|----------------|--------|---------|-------------------------------|--------------------------|-------|
| 0 ~ 63         | 10     | 6       | `11111111\|01`                | `'?'`                    | 64    |
| 64 ~ 95        | 11     | 5       | `11111111\|010 ~ 11111111\|011` | `'\''`                  | 32    |
| 96 ~ 127       | 11     | 5       | `11111111\|100`                | `'+'`                    | 32    |
| 128 ~ 159      | 11     | 5       | `11111111\|101`                | `'\|'`                   | 32    |
| 160 ~ 175      | 12     | 4       | `11111111\|1010`               | `'#'`                    | 16    |
| 176 ~ 191      | 12     | 4       | `11111111\|1011`               | `'>'`                    | 16    |
| 192 ~ 239      | 13     | 3       | `11111111\|11000 ~ 11111111\|11101` | NUL `'$'` `'@'` `'['` `']'` `'~'` | 48  |
| 240 ~ 247      | 14     | 2       | `11111111\|111100 ~ 11111111\|111101` | `'^'` `'}'`              | 8     |
| 248 ~ 253      | 15     | 1       | `11111111\|1111100 ~ 11111111\|1111110` | `'<'` `` '`' `` `'{'`    | 6     |
| 254 ~ 255      | —      | —       | —                             | 需继续查三级表（level=1）     | 2     |

> 注意：10-bit 码 `?` 的 follow 高 2 位为 `00`（6 位 mantissa 任意），因此 follow 0-63 全部映射到 `?`，而非仅 63。

### 解码判定

```java
// FF_LEVEL0[b] != 0 → 直接解码成功; =0 → 继续查三级表 (level=1+)
if (level == 0) return FF_LEVEL0[b] != 0 ? FF_LEVEL0[b] : val;
```

---

## 5. FE_10 — Level-0 二级查表（idx=254 路径）

```java
private static final int[] FE_10 = {
    0x600 | '!',    // follow 0~63
    0x600 | '"',    // follow 64~127
    0x600 | '(',    // follow 128~191
    0x600 | ')'     // follow 192~255
};
```

当一级表 `idx == 254` 时，follow 的取值范围为 0~255（2 bit 余码 × 1 字节），通过 `follow >> 6` 映射到 0~3 查表解码。对应 RFC 7541 中 4 个 10-bit Huffman 码。

---

## 6. 三级表（level=1）

当 `FF_LEVEL0` 判定需继续解码（`b >= 0xFE`，即 follow=254 或 255），进入 level=1。  
按 `val` 的第二个字节 `b1 = val >> 8 & 0xFF` 区分两条路径：

### 6.1 idx=254 路径（b1 != 0xFF，即 b1=0xFE）

| 数组     | 位长 | 前缀                        | 解码字符数 | 条目数 |
|---------|------|-----------------------------|-----------|-------|
| FE_19   | 19   | `11111111\|11111110\|000 ~ 010` | 3         | 3     |
| FE_20   | 20   | `11111111\|11111110\|0110 ~ 1101` | 8         | 8     |
| FE_21   | 21   | `11111111\|11111110\|11100 ~ 11111` | 4         | 4     |

### 6.2 idx=255 路径（b1 == 0xFF）

| 数组     | 位长 | 前缀                        | 解码字符数 | 条目数 |
|---------|------|-----------------------------|-----------|-------|
| FF_21   | 21   | `11111111\|11111111\|00000 ~ 01000` | 9         | 9     |
| FF_22   | 22   | `11111111\|11111111\|010010 ~ 101011` | 26        | 26    |
| FF_23   | 23   | `11111111\|11111111\|1011000 ~ 1110100` | 29        | 29    |
| FF_24   | 24   | `11111111\|11111111\|11101010 ~ 11110101` | 12        | 12    |

如果 b1==0xFF 且 b >= 0xF6，说明还有更深层（level=2），返回 `val` 继续。

---

## 7. 四级表（level=2）

当三级表判定需继续解码（`b >= 0xF6`），进入 level=2。  
按 `b1 = val >> 8 & 0xFF`（值的第 2 字节，范围 0xF6~0xFF）分 8 个 case：

| 数组        | b1   | 位长 | 解码字符数 | 条目数 |
|------------|------|------|-----------|-------|
| —          | 0xF6 | 25   | 2         | 2 (内联) |
| —          | 0xF7 | 25   | 2         | 2 (内联) |
| FF_F8_26   | 0xF8 | 26   | 4         | 4     |
| FF_F9_26   | 0xF9 | 26   | 4         | 4     |
| FF_FA_26   | 0xFA | 26   | 4         | 4     |
| FF_FB_26   | 0xFB | 26~27| 3+2       | 3+2(内联) |
| FF_FC_27   | 0xFC | 27   | 8         | 8     |
| FF_FD_27   | 0xFD | 27   | 8         | 8     |
| FF_FE_28   | 0xFE | 27~28| 1+14      | 14+1(内联) |
| FF_FF_28   | 0xFF | 28   | 15        | 15    |
| FF_FF_30   | 0xFF | 30   | 4         | 4     |

---

## 8. 全局总览

```
输入字节 b
    │
    ├─ idx 0~253 ────────── 一级表直接解码 (5~8 bit codes)
    │                         DECODE_VALUES / DECODE_BITS / DECODE_MANTISSAS
    │
    ├─ idx 254 ──────────── 二级表 FE_10 直接解码 (10 bit codes)
    │
    └─ idx 255 ──────────── 多字节解码循环
         │
         ├─ level 0 ────── FF_LEVEL0[follow]
         │    ├─ follow 63~253 ── 直接解码 (10~15 bit codes)
         │    ├─ follow 0~62 ─── 错误
         │    └─ follow 254~255 ─ 继续 ↓
         │
         ├─ level 1 ────── 三级表
         │    ├─ b1=0xFE ─── FE_19/FE_20/FE_21 (19~21 bit)
         │    ├─ b1=0xFF ─── FF_21/FF_22/FF_23/FF_24 (21~24 bit)
         │    └─ b>=0xF6 ── 继续 ↓
         │
         └─ level 2 ────── 四级表
              └─ b1 ∈ {0xF6~0xFF} ── 25~30 bit codes
```

### 统计

| 层级    | 表                          | 覆盖位长     | 解码字符数 |
|--------|-----------------------------|-------------|-----------|
| 一级   | DECODE_*                     | 5~8 bit     | 74 字符   |
| 二级   | FE_10                        | 10 bit      | 4 字符    |
| 二级   | FF_LEVEL0                    | 10~15 bit   | 19 字符   |
| 三级   | FE_19/20/21 + FF_21/22/23/24 | 19~24 bit   | 91 字符   |
| 四级   | FF_F8~FF_30                  | 25~30 bit   | 68 字符   |
| **合计** |                            | **5~30 bit** | **256 + EOS** |
