# Native runtime third-party notices

The APK bundles PRoot and its native dependencies for four Android ABIs. Source archives and their upstream license files are kept in [`third_party/sources/`](third_party/sources/).

| Component | Version / identification | License | Corresponding source |
|---|---|---|---|
| PRoot + loader | PRoot binary reports `5.1.107.102` | GPL-2.0-or-later | `sources/termux-proot-v5.1.107.102.tar.gz`; [upstream tag](https://github.com/termux/proot/tree/v5.1.107.102) |
| talloc | `2.5.0` source archive | LGPL-3.0-or-later | `sources/talloc-2.5.0.tar.gz`; [Samba source](https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz) |
| libandroid-shmem | `0.7` | BSD-3-Clause | `sources/termux-libandroid-shmem-v0.7.tar.gz`; [upstream tag](https://github.com/termux/libandroid-shmem/tree/v0.7) |

The license texts are included in the respective source archives (`COPYING` for PRoot, `LICENSE` for talloc and libandroid-shmem). PRoot's binary version was checked from its embedded version string; talloc and libandroid-shmem versions are identified from the upstream versions used by the runtime. No source changes are intentionally made to these projects. The native artifacts were not rebuilt from a checked-in reproducible build recipe as part of this release; the table provides the upstream corresponding sources and license notices.

## SHA-256 of bundled native artifacts

| ABI | `libproot.so` | `libproot-loader.so` | `libtalloc.so` | `libandroid-shmem.so` |
|---|---|---|---|---|
| `arm64-v8a` | `3621937c1c5a98d0d39ca599af573a6bc15128690606d535e8b05da00cec4f41` | `1da54355333cca3fb6ee10e3b82a24df24fb230c99241ce83809bf9e240609b5` | `742b438c4d09e276985a61d44164c9de207b6d8cc268f3018cb3001961bcb309` | `84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731` |
| `armeabi-v7a` | `cf954a8922d9f291ad7cd5b32329e97bffdcfff982c085d0441b26dce5a5a0f9` | `7c3d9f74b8c8a87c46766d8bbeebeabf24cc4de507690ab04ba7f0190a598500` | `a376ac7cbd18e6777657328c701b4979610473811761edc0fdb0ec388d8504af` | `ef35602fa6c8bca9081af1594fabeacbd98b6e8921dd6be54484291933b8040f` |
| `x86_64` | `3dd2e5b23aee8dd8588750117f03b1b7971314e7457067af803d78d57306391d` | `a836b0569c84b1661333d16bacf6b396ff08fea816a5c936a82e08ddeeca8341` | `7fa6722a4320495b1b85832fc7fd02de0cb367b45f116c1921b2830a6936da90` | `092926060298acd3778e6239033d7aef1280dcb59aebe021a3719612e6a3465f` |
| `x86` | `4a8dcd99a1d13e58ae9d8f4c0e0ea4ecba256af9d6585c044df93de03df9269c` | `8edc6f1cf6d0fe8bd9f47c416a2e770fd046bf3fd81f2739e2826344fab1d947` | `5645127eb513ce6ce74793701182c00e62015367ea463c75879c62c05924c570` | `85e80294b21e90cf02024d27a1f76fb37c81b633a6e532015db943cb27e92445` |

SHA-256 of source archives:

- PRoot: `36fb68838d86e491bc577064e23d365ea936e03ded4e90f62dc540907c04f385`
- talloc: `912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007`
- libandroid-shmem: `1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867`
