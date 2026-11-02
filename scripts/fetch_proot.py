#!/usr/bin/env python3
"""Download proot + its libraries from the Termux repo into jniLibs (arm64)."""
import glob, os, re, shutil, subprocess, sys, tempfile, urllib.request

BASE = "https://packages.termux.dev/apt/termux-main/"
OUT = os.path.join("app", "src", "main", "jniLibs", "arm64-v8a")
SKIP = {"libc", "libdl", "libm", "liblog", "libz", "libandroid"}
PREFIX = "data/data/com.termux/files/usr"


def get(url):
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


def stem(name):
    return re.sub(r"\.so.*$", "", name)


def field(stanza, name):
    m = re.search(r"^%s: (.+)$" % name, stanza, re.M)
    return m.group(1) if m else ""


print("Downloading package index...")
index = get(BASE + "dists/stable/main/binary-aarch64/Packages").decode()
pk = {}
for st in index.split("\n\n"):
    m = re.search(r"^Package: (.+)$", st, re.M)
    if m:
        pk[m.group(1)] = st
if "proot" not in pk:
    sys.exit("proot not found in Termux index")

order, seen, todo = [], set(), ["proot"]
while todo:
    n = todo.pop()
    if n in seen or n not in pk:
        continue
    seen.add(n)
    order.append(n)
    for d in field(pk[n], "Depends").split(","):
        d = re.sub(r"\s*\(.*\)", "", d.split("|")[0]).strip()
        if d:
            todo.append(d)
print("Packages:", order)

work = tempfile.mkdtemp()
roots = {}
for n in order:
    deb = os.path.join(work, n + ".deb")
    with open(deb, "wb") as f:
        f.write(get(BASE + field(pk[n], "Filename")))
    dest = os.path.join(work, "x_" + n)
    os.makedirs(dest)
    names = subprocess.check_output(["ar", "t", deb]).decode().split()
    data = [x for x in names if x.startswith("data.tar")][0]
    flag = {"data.tar.xz": "-J", "data.tar.gz": "-z", "data.tar.zst": "--zstd"}.get(data, "-J")
    ar = subprocess.Popen(["ar", "p", deb, data], stdout=subprocess.PIPE)
    subprocess.check_call(["tar", flag, "-x", "-C", dest], stdin=ar.stdout)
    ar.wait()
    roots[n] = os.path.join(dest, PREFIX)

proot_bin = os.path.join(roots["proot"], "bin", "proot")
loader = os.path.join(roots["proot"], "libexec", "proot", "loader")
for p in (proot_bin, loader):
    if not os.path.isfile(p):
        sys.exit("missing " + p)

libs = {}
for n in order:
    for p in glob.glob(os.path.join(roots[n], "lib", "*.so*")):
        real = os.path.realpath(p)
        s = stem(os.path.basename(p))
        if os.path.isfile(real) and s not in SKIP:
            libs.setdefault(s, real)

shutil.rmtree(OUT, ignore_errors=True)
os.makedirs(OUT)
shutil.copy(proot_bin, os.path.join(OUT, "libproot.so"))
shutil.copy(loader, os.path.join(OUT, "libproot-loader.so"))
for s, p in libs.items():
    shutil.copy(p, os.path.join(OUT, s + ".so"))

for f in sorted(glob.glob(os.path.join(OUT, "*.so"))):
    base = os.path.basename(f)
    if base == "libproot-loader.so":
        continue
    needed = subprocess.check_output(["patchelf", "--print-needed", f]).decode().split()
    for n in needed:
        s = stem(n)
        if s in libs and n != s + ".so":
            subprocess.check_call(["patchelf", "--replace-needed", n, s + ".so", f])
    subprocess.call(["patchelf", "--remove-rpath", f])
    if base != "libproot.so":
        subprocess.call(["patchelf", "--set-soname", base, f])

print("jniLibs:", sorted(os.listdir(OUT)))
print(subprocess.run(["readelf", "-d", os.path.join(OUT, "libproot.so")],
                     capture_output=True, text=True).stdout)
