# MPV Behaviour Verification

These scripts and C probes were used during development to investigate MPV/libmpv behaviour. Unless otherwise stated, the results were obtained with desktop MPV/libmpv 0.37 in the author's development environment.

## Tools

- `names.txt` and `check_docs.py` — Check the options, properties, and commands used by the engine against the MPV master documentation and the installed desktop MPV's `--list-*` output. The documentation check expects an MPV source clone at `../ref-mpv`.
- `t1.c`, `t2.c`, and `t3.c` — Small libmpv C API probes covering error codes, option information, JSON string conversion, and runtime property settability.

  Compile a probe with:
  ```bash
  gcc t1.c -o t1 $(pkg-config --cflags --libs mpv)
  ```
  Replace `t1.c` and `t1` with the desired probe's filename and output name.

- `probe.py` and `probe2.py` — Measure properties and latency for live HLS, VOD HLS, and MP4 playback, including comparisons between latency profiles. These scripts require the test sources or simulated streams provided by `start_sim.sh`.
- `probe_http_headers.py` — Investigate quoting and formatting of `http-header-fields`, `user-agent`, and `referrer` when supplied as per-file options.

## Scope and limitations

These are development verification tools, not a complete automated test suite. Results from desktop MPV/libmpv 0.37 do not guarantee identical behaviour in the Android native libraries bundled with this project. Android-specific behaviour must be verified separately on the target device and ABI.
