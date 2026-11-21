#!/usr/bin/env python3
"""Target-side receiver for the Linux spike: records what the X display actually received.

usage: linux-receiver.py DIR   (DISPLAY must point at the Xvfb that the RDP server shadows)
"""
import json, os, sys, time, datetime
import tkinter as tk

D = sys.argv[1]
os.makedirs(D, exist_ok=True)


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


events = []


def log(s):
    events.append(now() + "|" + s)


root = tk.Tk()
root.geometry("1280x800+0+0")
root.configure(bg="#466ea0")
inp = tk.Text(root, width=70, height=18, wrap="word", undo=False)
inp.place(x=20, y=20)
btn = tk.Button(root, text="CLICK-A", width=18, height=4, command=lambda: log("Click|btnA"))
btn.place(x=20, y=430)
pnl = tk.Canvas(root, width=300, height=200, bg="white", highlightthickness=0)
pnl.place(x=260, y=430)
scr = tk.Text(root, width=50, height=24, wrap="none")
scr.place(x=700, y=20)
scr.insert("1.0", "\n".join("line %03d" % i for i in range(1, 401)))
scr.configure(state="disabled")

inp.bind("<KeyPress>", lambda e: log("KeyPress|%s|U+%04X" % (e.keysym, ord(e.char) if e.char else 0)))
for name, w in (("panel", pnl), ("btnA", btn)):
    w.bind("<ButtonPress>", lambda e, n=name: log("MouseDown|%s|%s|%d,%d|screen=%d,%d" % (n, e.num, e.x, e.y, e.x_root, e.y_root)))
    w.bind("<ButtonRelease>", lambda e, n=name: log("MouseUp|%s|%s" % (n, e.num)))
scr.bind("<ButtonPress-4>", lambda e: log("Wheel|scroll|up"))
scr.bind("<ButtonPress-5>", lambda e: log("Wheel|scroll|down"))

last_text = [None]
last_state = [""]
reset_n = [0]


def write(path, data):
    tmp = path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, path)


def tick():
    try:
        if events:
            with open(os.path.join(D, "events.log"), "a", encoding="utf-8") as f:
                f.write("\n".join(events) + "\n")
            events.clear()
        flag = os.path.join(D, "reset.flag")
        if os.path.exists(flag):
            inp.delete("1.0", "end")
            os.remove(flag)
            reset_n[0] += 1
            write(os.path.join(D, "reset.ack"), json.dumps({"n": reset_n[0]}).encode())
            inp.focus_force()
        t = inp.get("1.0", "end-1c")
        if t != last_text[0]:
            write(os.path.join(D, "text.bin"), t.encode("utf-8", "surrogatepass"))
            last_text[0] = t
        st = json.dumps({
            "cursor": {"x": root.winfo_pointerx(), "y": root.winfo_pointery()},
            "firstVisibleLine": int(scr.index("@0,0").split(".")[0]),
            "focused": "input" if root.focus_get() is inp else str(root.focus_get()),
            "tbLength": len(t)})
        if st != last_state[0]:
            write(os.path.join(D, "state.json"), st.encode())
            last_state[0] = st
    except Exception as ex:  # keep the receiver alive
        with open(os.path.join(D, "receiver-error.log"), "a") as f:
            f.write(repr(ex) + "\n")
    root.after(50, tick)


def center(w):
    return {"x": w.winfo_rootx() + w.winfo_width() // 2, "y": w.winfo_rooty() + w.winfo_height() // 2}


def ready():
    root.update_idletasks()
    inp.focus_force()
    lay = {"pid": os.getpid(), "screen": {"w": root.winfo_screenwidth(), "h": root.winfo_screenheight()},
           "input": center(inp), "button": center(btn), "panel": center(pnl), "scroll": center(scr), "startedUtc": now()}
    write(os.path.join(D, "ready.json"), json.dumps(lay).encode())
    tick()


root.after(500, ready)
root.mainloop()
