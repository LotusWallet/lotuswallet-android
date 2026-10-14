# -*- coding: utf-8 -*-
"""
生成非 EVM 链（Solana / ICP / Monero / Filecoin）的**黄金向量**，供单元测试当期望值用。

## 为什么需要这个脚本，而不是直接用钱包或在线工具算
本机无外网，无法用官方工具或第三方库交叉核对；而地址派生写错的后果是
「格式合法但没人能解锁」的**静默**错误资产 —— 所以期望值必须**本地自证**。

## 用法
    python tools/gen_chain_vectors.py

脚本会先跑三重锚定，**任一锚定不过就直接 assert 退出**（后续向量不可信）：
  1. BIP-32 Test vector 1 的官方 xprv 串（m / m/0' / m/0'/1 / m/0'/1/2'）；
  2. RFC 8032 ed25519 已知向量 + 群阶检查（L·G = 单位元）；
  3. SLIP-0010 ed25519 Test vector 1。
锚定通过后再打印各链的私钥 / 中间值 / 地址，把它们抄进对应的 Kotlin 测试。

## 上一版脚本的两个 bug（引以为戒）
  1. BIP-32 私有子密钥少加了父密钥。`ki = parse256(IL) + kpar (mod n)` 对**硬化与非硬化
     都成立**，这一点最反直觉；
  2. 路径 `m/44'/223'/0'/0/0` 里最后两层是**非硬化**，脚本却按硬化处理。
     非硬化要用**压缩**公钥参与 HMAC 输入。
后果是 ICP / Filecoin 的期望值全错，一度把实现误判成有 bug。
**教训：先让向量生成器自己通过官方向量，再拿它去核对实现；顺序反了就会白查半天。**

## 依赖
只需要 `pycryptodome`（用于 Keccak），ed25519 与 secp256k1 都在本文件里独立实现。
"""

import base64
import binascii
import hashlib
import hmac
import zlib

# ============================================================ secp256k1

P = 2 ** 256 - 2 ** 32 - 977
N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
GX = 0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798
GY = 0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8


def _add(p, q):
    if p is None:
        return q
    if q is None:
        return p
    x1, y1 = p
    x2, y2 = q
    if x1 == x2 and (y1 + y2) % P == 0:
        return None
    if p == q:
        lam = (3 * x1 * x1) * pow(2 * y1, P - 2, P) % P
    else:
        lam = (y2 - y1) * pow(x2 - x1, P - 2, P) % P
    x3 = (lam * lam - x1 - x2) % P
    return x3, (lam * (x1 - x3) - y1) % P


def _mul(k, pt=(GX, GY)):
    r = None
    while k:
        if k & 1:
            r = _add(r, pt)
        pt = _add(pt, pt)
        k >>= 1
    return r


def point(k):
    return _mul(k % N)


def pub_compressed(k):
    pt = point(k)
    return bytes([2 + (pt[1] & 1)]) + pt[0].to_bytes(32, 'big')


def pub_uncompressed(k):
    pt = point(k)
    return b'\x04' + pt[0].to_bytes(32, 'big') + pt[1].to_bytes(32, 'big')


# ============================================================ BIP-32

def bip32_master(seed):
    i = hmac.new(b'Bitcoin seed', seed, hashlib.sha512).digest()
    return i[:32], i[32:]


def bip32_child(key, chain, index):
    """index 已含硬化位。硬化与非硬化都做 ki = IL + kpar mod n。"""
    if index & 0x80000000:
        data = b'\x00' + key + index.to_bytes(4, 'big')
    else:
        data = pub_compressed(int.from_bytes(key, 'big')) + index.to_bytes(4, 'big')
    i = hmac.new(chain, data, hashlib.sha512).digest()
    child = (int.from_bytes(i[:32], 'big') + int.from_bytes(key, 'big')) % N
    assert child != 0
    return child.to_bytes(32, 'big'), i[32:]


def _segments(path):
    body = path.strip()
    if body.startswith('m/'):
        body = body[2:]
    elif body in ('m', 'M', ''):
        return []
    elif body.startswith('M/'):
        body = body[2:]
    return [s for s in body.split('/') if s]


def bip32_parse(path):
    out = []
    for seg in _segments(path):
        hard = seg[-1] in "'hH"
        digits = seg.rstrip("'hH")
        v = int(digits)
        out.append(v | 0x80000000 if hard else v)
    return out


def bip32_derive(seed, path):
    key, chain = bip32_master(seed)
    for idx in bip32_parse(path):
        key, chain = bip32_child(key, chain, idx)
    return key, chain


B58A = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz'


def b58check(payload):
    chk = hashlib.sha256(hashlib.sha256(payload).digest()).digest()[:4]
    n = int.from_bytes(payload + chk, 'big')
    s = ''
    while n:
        n, r = divmod(n, 58)
        s = B58A[r] + s
    for b in payload + chk:
        if b == 0:
            s = '1' + s
        else:
            break
    return s


def ser_xprv(key, chain, depth, parent_fp, child_num):
    body = (bytes.fromhex('0488ade4') + bytes([depth]) + parent_fp.to_bytes(4, 'big')
            + child_num.to_bytes(4, 'big') + chain + b'\x00' + key)
    return b58check(body)


def fingerprint(key):
    h = hashlib.new('ripemd160', hashlib.sha256(pub_compressed(int.from_bytes(key, 'big'))).digest()).digest()
    return int.from_bytes(h[:4], 'big')


# ---- 锚定：BIP-32 Test vector 1 ------------------------------------
def ser32(i):
    return (i & 0xFFFFFFFF).to_bytes(4, 'big')


def xprv_for(seed, path):
    """沿路径派生并序列化。深度/父指纹/子编号全部自动推导，避免手打污染向量。"""
    key, chain = bip32_master(seed)
    depth, parent_fp, child_num = 0, 0, 0
    for idx in bip32_parse(path):
        parent_fp = fingerprint(key)
        key, chain = bip32_child(key, chain, idx)
        depth += 1
        child_num = idx
    body = (bytes.fromhex('0488ade4') + bytes([depth]) + ser32(parent_fp)
            + ser32(child_num) + chain + b'\x00' + key)
    return b58check(body), key, chain


V1_SEED = bytes.fromhex('000102030405060708090a0b0c0d0e0f')
# 只锚定官方公布的 xprv 串：它同时锁住私钥、链码、深度、父指纹、子编号 ——
# 任何一环写错都串不出来。手打「私钥/链码/父指纹」反而容易被记忆污染
# （这一版就是因为手打的两个字段是错的，才误判成实现有问题）。
ANCHORS = [
    ("m", 'xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi'),
    ("m/0'", 'xprv9uHRZZhk6KAJC1avXpDAp4MDc3sQKNxDiPvvkX8Br5ngLNv1TxvUxt4cV1rGL5hj6KCesnDYUhd7oWgT11eZG7XnxHrnYeSvkzY7d2bhkJ7'),
    ("m/0'/1", 'xprv9wTYmMFdV23N2TdNG573QoEsfRrWKQgWeibmLntzniatZvR9BmLnvSxqu53Kw1UmYPxLgboyZQaXwTCg8MSY3H2EU4pWcQDnRnrVA1xe8fs'),
    ("m/0'/1/2'", 'xprv9z4pot5VBttmtdRTWfWQmoH1taj2axGVzFqSb8C9xaxKymcFzXBDptWmT7FwuEzG3ryjH4ktypQSAewRiNMjANTtpgP4mLTj34bhnZX7UiM'),
]

print('=' * 70)
print('锚定 1：BIP-32 Test vector 1（比对官方 xprv 串）')
ok = True
for path, expr in ANCHORS:
    ser, k, c = xprv_for(V1_SEED, path)
    good = (ser == expr)
    ok = ok and good
    print(f'  {path:<14} {"OK" if good else "BAD"}')
    if not good:
        print(f'      ours {ser}')
        print(f'      want {expr}')
    print(f'      priv {k.hex()}')
    print(f'      cc   {c.hex()}')
print('  锚定结果:', 'PASS' if ok else 'FAIL')
assert ok, 'BIP-32 实现与官方向量不符，后续向量不可信'

# ============================================================ ed25519

Q = 2 ** 255 - 19
L = 2 ** 252 + 27742317777372353535851937790883648493
D = (-121665 * pow(121666, Q - 2, Q)) % Q
I = pow(2, (Q - 1) // 4, Q)


def _xrecover(y):
    xx = (y * y - 1) * pow(D * y * y + 1, Q - 2, Q) % Q
    x = pow(xx, (Q + 3) // 8, Q)
    if (x * x - xx) % Q != 0:
        x = x * I % Q
    if x % 2 != 0:
        x = Q - x
    return x


BY = 4 * pow(5, Q - 2, Q) % Q
BX = _xrecover(BY)
B = (BX, BY)
IDENT = (0, 1)


def eadd(p, q):
    x1, y1 = p
    x2, y2 = q
    k = D * x1 * x2 * y1 * y2 % Q
    x3 = (x1 * y2 + x2 * y1) * pow(1 + k, Q - 2, Q) % Q
    y3 = (y1 * y2 + x1 * x2) * pow(1 - k, Q - 2, Q) % Q
    return x3, y3


def emul(k, pt=B):
    r = IDENT
    while k:
        if k & 1:
            r = eadd(r, pt)
        pt = eadd(pt, pt)
        k >>= 1
    return r


def ecompress(pt):
    x, y = pt
    return (y | ((x & 1) << 255)).to_bytes(32, 'little')


def ed25519_pub(seed):
    h = hashlib.sha512(seed).digest()
    a = int.from_bytes(bytes([h[0] & 248]) + h[1:31] + bytes([(h[31] & 63) | 64]), 'little')
    return ecompress(emul(a))


def sc_reduce32(b):
    """Monero 的 sc_reduce32：32 字节小端 → mod L → 32 字节小端。"""
    return (int.from_bytes(b, 'little') % L).to_bytes(32, 'little')


def scalar_mult_base(scalar_le):
    return ecompress(emul(int.from_bytes(scalar_le, 'little')))


print('=' * 70)
print('锚定 2：RFC 8032 ed25519 已知向量')
rfc = [
    ('9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60',
     'd75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a'),
    ('4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb',
     '3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c'),
]
for sk, pk in rfc:
    got = ed25519_pub(bytes.fromhex(sk)).hex()
    print(f'  {sk[:16]}… -> {got[:32]}… {"OK" if got == pk else "BAD"}')
    assert got == pk, 'ed25519 实现不对'
# 群阶：L·G 必须是单位元
assert emul(L) == IDENT, '子群阶不对'
assert emul(1) == B
print('  群阶检查 L·G = 单位元 OK；1·G = 基点 OK')

# ============================================================ SLIP-0010

def slip10_master(seed):
    i = hmac.new(b'ed25519 seed', seed, hashlib.sha512).digest()
    return i[:32], i[32:]


def slip10_child(key, chain, index):
    i = hmac.new(chain, b'\x00' + key + (index | 0x80000000).to_bytes(4, 'big'), hashlib.sha512).digest()
    return i[:32], i[32:]          # ed25519：ki = IL，不加父密钥


def slip10_derive(seed, path):
    key, chain = slip10_master(seed)
    for seg in _segments(path):
        key, chain = slip10_child(key, chain, int(seg.rstrip("'hH")))
    return key


print('=' * 70)
print('锚定 3：SLIP-0010 ed25519 Test vector 1')
SV1 = bytes.fromhex('000102030405060708090a0b0c0d0e0f')
for path, ek, ec in [
    ('m', '2b4be7f19ee27bbf30c667b642d5f4aa69fd169872f8fc3059c08ebae2eb19e7',
     '90046a93de5380a72b5e45010748567d5ea02bbf6522f979e05c0d8d8ca9fffb'),
    ("m/0'", '68e0fe46dfb67e368c75379acec591dad19df3cde26e63b93a8e704f1dade7a3',
     '8b59aa11380b624e81507a27fedda59fea6d0b779a778918a2fd3590e16e9c69'),
    ("m/0'/1'/2'/2'/1000000000'",
     '8f94d394a8e8fd6b1bc2f3f49f5c47e385281d5c17e65324b0f62483e37e8793',
     '68789923a0cac2cd5a29172a475fe9e0fb14cd6adb5ad98a3fa70333e7afa230'),
]:
    key, chain = slip10_master(SV1)
    for seg in _segments(path):
        key, chain = slip10_child(key, chain, int(seg.rstrip("'hH")))
    print(f'  {path:<26} {"OK" if key.hex() == ek and chain.hex() == ec else "BAD"}')
    assert key.hex() == ek and chain.hex() == ec, path

# ============================================================ 编码工具

B32A = 'abcdefghijklmnopqrstuvwxyz234567'


def b32_nopad(data):
    return base64.b32encode(data).decode().rstrip('=').lower()


def principal_from_raw(raw):
    crc = zlib.crc32(raw) & 0xFFFFFFFF
    body = crc.to_bytes(4, 'big') + raw
    text = b32_nopad(body)
    return '-'.join(text[i:i + 5] for i in range(0, len(text), 5))


ED25519_SPKI = bytes.fromhex('302a300506032b6570032100')

MNE = ('abandon abandon abandon abandon abandon abandon '
       'abandon abandon abandon abandon abandon about')
SEED = hashlib.pbkdf2_hmac('sha512', MNE.encode(), b'mnemonic', 2048, 64)
print('=' * 70)
print('助记词种子:', SEED.hex())

# ---- Solana ---------------------------------------------------------
print('-' * 70)
print('Solana  m/44\'/501\'/account\'/0\'  (SLIP-0010)')
for acc in (0, 1):
    path = f"m/44'/501'/{acc}'/0'"
    mat = slip10_derive(SEED, path)
    pub = ed25519_pub(mat)
    n = int.from_bytes(pub, 'big')
    s = ''
    while n:
        n, r = divmod(n, 58)
        s = B58A[r] + s
    print(f'  acc={acc} path={path}')
    print(f'    material  {mat.hex()}')
    print(f'    pubkey    {pub.hex()}')
    print(f'    address   {s}')
    # 43 字符（少一位）解码后多少字节
    n43 = int.from_bytes(pub, 'big')
    t = s[:-1]
    v = 0
    for ch in t:
        v = v * 58 + B58A.index(ch)
    print(f'    (少1字符) {t} -> {(v.bit_length() + 7) // 8} 字节')
    t5 = s[:-5]
    v = 0
    for ch in t5:
        v = v * 58 + B58A.index(ch)
    print(f'    (少5字符) {t5} -> {(v.bit_length() + 7) // 8} 字节')

# ---- ICP ------------------------------------------------------------
print('-' * 70)
print('ICP  m/44\'/223\'/account\'/0/0  (BIP-32 + ed25519)')
for acc in (0, 1):
    path = f"m/44'/223'/{acc}'/0/0"
    key, chain = bip32_derive(SEED, path)
    pub = ed25519_pub(key)
    raw = hashlib.sha224(ED25519_SPKI + pub).digest()
    print(f'  acc={acc} path={path}')
    print(f'    material  {key.hex()}')
    print(f'    pubkey    {pub.hex()}')
    print(f'    raw(28B)  {raw.hex()}')
    print(f'    principal {principal_from_raw(raw)}')

# ---- Filecoin -------------------------------------------------------
print('-' * 70)
print('Filecoin  m/44\'/461\'/account\'/0/0  (BIP-32 + blake2b-160)')
for acc in (0, 1):
    path = f"m/44'/461'/{acc}'/0/0"
    key, chain = bip32_derive(SEED, path)
    pubu = pub_uncompressed(int.from_bytes(key, 'big'))
    payload = hashlib.blake2b(pubu, digest_size=20).digest()
    ck = hashlib.blake2b(b'\x01' + payload, digest_size=4).digest()
    addr = 'f1' + b32_nopad(payload + ck)
    print(f'  acc={acc} path={path}')
    print(f'    privkey   {key.hex()}')
    print(f'    pub(未压缩) {pubu.hex()}')
    print(f'    payload   {payload.hex()}   ck {ck.hex()}')
    print(f'    address   {addr}  (长度 {len(addr)})')

# ---- Monero ---------------------------------------------------------
print('-' * 70)
print('Monero  m/44\'/128\'/account\'  (SLIP-0010 + 标量乘法)')
MB58A = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz'
MB58_BLOCK = [0, 2, 3, 5, 6, 7, 9, 10, 11]
MB58_ENC = {1: 2, 2: 3, 3: 5, 4: 6, 5: 7, 6: 9, 7: 10, 8: 11}


def mb58_encode(data):
    out = ''
    for i in range(0, len(data), 8):
        block = data[i:i + 8]
        size = MB58_ENC[len(block)]
        num = int.from_bytes(block, 'big')
        s = ''
        for _ in range(size):
            num, r = divmod(num, 58)
            s = MB58A[r] + s
        out += s
    return out


for acc in (0, 1):
    path = f"m/44'/128'/{acc}'"
    mat = slip10_derive(SEED, path)
    spend = sc_reduce32(mat)
    view = sc_reduce32(hashlib.new('sha3_256').digest())  # 占位，下面用 keccak 重算
    from Crypto.Hash import keccak as _k
    view = sc_reduce32(_k.new(digest_bits=256, data=spend).digest())
    body = bytes([18]) + scalar_mult_base(spend) + scalar_mult_base(view)
    chk = _k.new(digest_bits=256, data=body).digest()[:4]
    addr = mb58_encode(body + chk)
    print(f'  acc={acc} path={path}')
    print(f'    material  {mat.hex()}')
    print(f'    spend     {spend.hex()}')
    print(f'    view      {view.hex()}')
    print(f'    address   {addr}  (长度 {len(addr)})')
print('=' * 70)
print('DONE')
