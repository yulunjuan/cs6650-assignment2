/Started/ {
    if (n) printf "run ending %s: processed=%s maxLag=%dms maxThreads=%s duplicates=%s\n", t, p, ml, mt, d
    ml = 0; mt = 0; n = 0
}
/processed=/ {
    n = 1; t = $1
    for (i = 1; i <= NF; i++) {
        split($i, a, "=")
        if (a[1] == "processed") p = a[2]
        if (a[1] == "avgLag") { v = a[2]; sub("ms", "", v); if (v + 0 > ml) ml = v + 0 }
        if (a[1] == "threads" && a[2] + 0 > mt) mt = a[2] + 0
        if (a[1] == "duplicates") d = a[2]
    }
}
END {
    if (n) printf "run ending %s: processed=%s maxLag=%dms maxThreads=%s duplicates=%s\n", t, p, ml, mt, d
}