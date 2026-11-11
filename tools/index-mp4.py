#!/usr/bin/env python3
"""Find fragmented MP4s with no seek index (no sidx) and remux them so they can seek.

ExoPlayer treats these as unseekable: every seek goes to 0, and the app has to fall back to VLC.
yt-dlp's DASH downloads are the usual source. Remuxing is a stream copy (no re-encode) into a
regular MP4 with the index up front.

    tools/index-mp4.py /Volumes/Media            # list files that need it
    tools/index-mp4.py /Volumes/Media --fix      # remux them in place
    tools/index-mp4.py file.mp4 --fix            # one file (yt-dlp: --exec "after_move:index-mp4.py --fix {}")
    tools/index-mp4.py --self-test
"""
import os
import struct
import subprocess
import sys
import tempfile

EXTS = {".mp4", ".m4v", ".mov"}


def top_boxes(path):
    """Top-level box types, reading only box headers."""
    types = []
    with open(path, "rb") as f:
        end = os.fstat(f.fileno()).st_size
        pos = 0
        while pos + 8 <= end:
            f.seek(pos)
            size, kind = struct.unpack(">I4s", f.read(8))
            if size == 1:
                size = struct.unpack(">Q", f.read(8))[0]
            elif size == 0:
                size = end - pos
            if size < 8:
                break  # corrupt; stop rather than loop
            types.append(kind.decode("latin-1"))
            pos += size
    return types


def needs_index(path):
    t = top_boxes(path)
    return "moof" in t and "sidx" not in t  # ExoPlayer seeks fragmented MP4 by sidx only, never mfra


def duration(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
        capture_output=True, text=True,
    ).stdout.strip()
    return float(out) if out else 0.0


def fix(path):
    """Remux next to the original, check it, then swap it in. The original stays if anything fails."""
    base, ext = os.path.splitext(path)
    tmp = f"{base}.indexing{ext}"
    st = os.stat(path)
    r = subprocess.run(
        ["ffmpeg", "-nostdin", "-v", "error", "-y", "-i", path, "-map", "0", "-c", "copy",
         "-movflags", "+faststart", tmp],
        capture_output=True, text=True,
    )
    try:
        if r.returncode != 0:
            raise RuntimeError(r.stderr.strip().splitlines()[-1] if r.stderr.strip() else "ffmpeg failed")
        if needs_index(tmp):
            raise RuntimeError("output still unindexed")
        a, b = duration(path), duration(tmp)
        if abs(a - b) > max(2.0, a * 0.01):
            raise RuntimeError(f"duration changed {a:.1f}s -> {b:.1f}s")
        os.replace(tmp, path)
        os.utime(path, (st.st_atime, st.st_mtime))  # keep folder sort order by date
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)


def scan(root):
    if os.path.isfile(root):  # a single file, e.g. from yt-dlp --exec
        if os.path.splitext(root)[1].lower() in EXTS and needs_index(root):
            yield root
        return
    for d, dirs, files in os.walk(root):
        dirs[:] = [x for x in dirs if not x.startswith(".")]
        for name in sorted(files):
            if name.startswith(".") or ".indexing." in name or os.path.splitext(name)[1].lower() not in EXTS:
                continue
            p = os.path.join(d, name)
            try:
                if needs_index(p):
                    yield p
            except OSError as e:
                print(f"skip {p}: {e}", file=sys.stderr)


def self_test():
    with tempfile.TemporaryDirectory() as d:
        p = os.path.join(d, "frag.mp4")
        subprocess.run(
            ["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc=d=6:s=320x240:r=30", "-c:v", "libx264",
             "-g", "30", "-movflags", "frag_keyframe+empty_moov+default_base_moof", p],
            check=True,
        )
        assert needs_index(p), top_boxes(p)
        assert list(scan(d)) == [p]
        assert list(scan(p)) == [p]
        fix(p)
        assert not needs_index(p), top_boxes(p)
        assert top_boxes(p).index("moov") < top_boxes(p).index("mdat")
        assert list(scan(d)) == []
    print("self-test ok")


if __name__ == "__main__":
    args = sys.argv[1:]
    if args == ["--self-test"]:
        self_test()
        sys.exit()
    do_fix = "--fix" in args
    roots = [a for a in args if a != "--fix"]
    if len(roots) != 1:
        sys.exit(__doc__)
    found = failed = 0
    for p in scan(roots[0]):
        found += 1
        if not do_fix:
            print(p)
            continue
        print(f"indexing {p}", flush=True)
        try:
            fix(p)
        except Exception as e:  # keep going; the original is untouched
            failed += 1
            print(f"  FAILED: {e}", file=sys.stderr)
    verb = "fixed" if do_fix else "need indexing"
    print(f"{found - failed} {verb}" + (f", {failed} failed" if failed else ""), file=sys.stderr)
