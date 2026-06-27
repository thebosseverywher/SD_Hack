"""Command-line interface — ``flow serve | index | ask | pair``.

Examples::

    flow serve                       # start the web UI + federation server
    flow index ./docs                # index a folder of documents (Files sensor)
    flow index ./photos --trove      # index images (Trove sensor)
    flow ask "what's the office wifi password"
    flow pair                        # print pairing JSON + write a QR PNG
    flow trail --duration 60         # run the Windows activity timeline (Win only)
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import List, Optional

from . import config
from .index import Index


def _cmd_serve(args: argparse.Namespace) -> int:
    from .server import serve
    serve(host=args.host, port_http=args.port, db_path=args.db, device_id=args.device_id)
    return 0


def _cmd_index(args: argparse.Namespace) -> int:
    index = Index(args.db)
    if args.trove:
        from .sensors import trove as sensor
        stats = sensor.index_folder(index, args.path, args.device_id, use_ocr=not args.no_ocr)
    else:
        from .sensors import files as sensor
        stats = sensor.index_folder(index, args.path, args.device_id)
    print(json.dumps(stats, indent=2))
    print(f"index now holds {index.count()} items "
          f"(backend: {'sqlite-vec' if index.vec_enabled else 'numpy-fallback'})")
    return 0


def _cmd_ask(args: argparse.Namespace) -> int:
    from .brain import Brain
    index = Index(args.db)
    brain = Brain(index, federation=None, device_id=args.device_id)
    result = brain.ask(args.query, top_k=args.top_k)
    print("\nANSWER:\n" + result["answer"])
    if result["sources"]:
        print("\nSOURCES:")
        for s in result["sources"]:
            print(f"  - item={s['item_id']} device={s['device_id']} file={s.get('file_ref')}")
    return 0


def _cmd_pair(args: argparse.Namespace) -> int:
    from .federation import make_pairing_payload, make_qr_png
    payload = make_pairing_payload(port=config.WS_PORT)
    print(json.dumps(payload, indent=2))
    out = Path(args.out).expanduser()
    out.write_bytes(make_qr_png(payload))
    print(f"\nQR written to {out}")
    return 0


def _cmd_trail(args: argparse.Namespace) -> int:
    from .sensors import trail_win
    index = Index(args.db)
    ok = trail_win.run(index, device_id=args.device_id, duration_s=args.duration)
    if not ok:
        print("Trail not available on this platform (needs Windows + pywin32).",
              file=sys.stderr)
        return 1
    print(f"trail captured; index now holds {index.count()} items")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="flow", description="Flow desktop engine")
    p.add_argument("--db", default=None, help="path to the SQLite DB (default ~/.flow/flow.db)")
    p.add_argument("--device-id", default=None, help="override device id")
    sub = p.add_subparsers(dest="command", required=True)

    s = sub.add_parser("serve", help="run the web UI + federation server")
    s.add_argument("--host", default="127.0.0.1")
    s.add_argument("--port", type=int, default=8000, help="HTTP/UI port")
    s.set_defaults(func=_cmd_serve)

    i = sub.add_parser("index", help="index a folder")
    i.add_argument("path", help="folder to index")
    i.add_argument("--trove", action="store_true", help="index images (Trove) instead of docs")
    i.add_argument("--no-ocr", action="store_true", help="disable OCR for Trove")
    i.set_defaults(func=_cmd_index)

    a = sub.add_parser("ask", help="ask a question (local index only)")
    a.add_argument("query")
    a.add_argument("--top-k", type=int, default=config.TOP_K)
    a.set_defaults(func=_cmd_ask)

    pr = sub.add_parser("pair", help="produce pairing JSON + QR PNG")
    pr.add_argument("--out", default="flow-pair.png")
    pr.set_defaults(func=_cmd_pair)

    t = sub.add_parser("trail", help="run the Windows activity timeline")
    t.add_argument("--duration", type=float, default=None, help="seconds (default: forever)")
    t.set_defaults(func=_cmd_trail)
    return p


def main(argv: Optional[List[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
