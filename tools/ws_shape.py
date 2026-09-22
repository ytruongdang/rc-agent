#!/usr/bin/env python3
"""macOS stand-in for `tc netem`: delay / loss / rate-limit a local WS hop."""
import argparse
import asyncio
import random
import time


async def pipe(src, dst, delay, loss, bps, budget):
    try:
        while True:
            data = await src.read(65536)
            if not data:
                break
            if random.random() < loss:
                continue
            if bps > 0:
                wait = len(data) * 8 / bps
                now = time.monotonic()
                budget[0] = max(budget[0], now) + wait
                sleep = budget[0] - now
                if sleep > 0:
                    await asyncio.sleep(sleep)
            if delay > 0:
                await asyncio.sleep(delay)
            dst.write(data)
            await dst.drain()
    except (ConnectionResetError, BrokenPipeError, asyncio.IncompleteReadError):
        pass
    finally:
        dst.close()


async def handle(reader, writer, upstream_host, upstream_port, delay, loss, bps):
    try:
        up_r, up_w = await asyncio.open_connection(upstream_host, upstream_port)
    except OSError as e:
        writer.close()
        print("upstream", e)
        return
    budget_up = [time.monotonic()]
    budget_dn = [time.monotonic()]
    await asyncio.gather(
        pipe(reader, up_w, 0, 0, 0, budget_up),
        pipe(up_r, writer, delay, loss, bps, budget_dn),
        return_exceptions=True,
    )


async def main():
    p = argparse.ArgumentParser()
    p.add_argument("--listen", type=int, default=13002)
    p.add_argument("--upstream-host", default="127.0.0.1")
    p.add_argument("--upstream-port", type=int, default=13001)
    p.add_argument("--delay-ms", type=float, default=120)
    p.add_argument("--loss", type=float, default=0.02)
    p.add_argument("--kbps", type=float, default=800)
    args = p.parse_args()
    delay = args.delay_ms / 1000.0
    bps = args.kbps * 1000.0
    server = await asyncio.start_server(
        lambda r, w: handle(r, w, args.upstream_host, args.upstream_port, delay, args.loss, bps),
        "127.0.0.1",
        args.listen,
    )
    print(f"shape :{args.listen} -> {args.upstream_host}:{args.upstream_port} delay={args.delay_ms}ms loss={args.loss} {args.kbps}kbps")
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
