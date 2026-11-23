import argparse
import hashlib
import json
import logging
from pathlib import Path
import sys

from .api import ApiError, MikaApi
from .config import default_config, load_config
from .locking import instance_lock


def state_file(config):
    identity = hashlib.sha256((config["feishu_app_id"] + "\n" + config["mika_url"].rstrip("/")).encode()).hexdigest()[:16]
    return config["_dir"] / ("state-" + identity + ".sqlite3")


def main():
    parser = argparse.ArgumentParser(description="Connect a Feishu bot to Mika on your phone")
    parser.add_argument("--config", type=Path, default=default_config())
    commands = parser.add_subparsers(dest="command", required=True)
    setup_parser = commands.add_parser("setup", help="Scan to create/link a bot, or enter credentials manually")
    setup_parser.add_argument("--mode", choices=["create", "existing", "manual"])
    setup_parser.add_argument("--no-browser", action="store_true")
    commands.add_parser("doctor", help="Check phone API and Feishu app authentication without sending a task/message")
    commands.add_parser("run", help="Listen for messages from a locally paired Feishu user")
    commands.add_parser("unpair", help="Remove the locally paired owner; stop run first")
    args = parser.parse_args()
    logging.basicConfig(level=logging.ERROR, format="%(levelname)s: %(message)s")
    try:
        if args.command == "setup":
            from .onboarding import setup
            args.config.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            with instance_lock(args.config.parent / "connector.lock"):
                setup(args.config, args.mode, not args.no_browser)
            return 0
        config = load_config(args.config)
        if args.command == "doctor":
            report = {}
            try:
                info = MikaApi(config["mika_url"], config["mika_token"]).check(force=True)
                report["phone"] = {"ok": True, "api_version": info["api_version"],
                                   "server_version": info.get("server_version"), "capabilities": info["capabilities"]}
            except (ApiError, ValueError) as exc:
                report["phone"] = {"ok": False, "code": exc.code if isinstance(exc, ApiError) else "invalid_url"}
            from .runtime import check_feishu
            report["feishu"] = check_feishu(config)
            import sqlite3
            count = len(config.get("allowed_open_ids", []))
            database = state_file(config)
            if database.exists():
                with sqlite3.connect(database.as_uri() + "?mode=ro", uri=True) as connection:
                    count += connection.execute("SELECT count(*) FROM owners").fetchone()[0]
            report["pairing"] = {"paired": count > 0, "allowed_user_count": count}
            report["event_subscription"] = "unverified; confirm by sending a private message to the bot"
            report["end_to_end_verified"] = False
            print(json.dumps(report, ensure_ascii=False, indent=2))
            return 0 if report["phone"]["ok"] and report["feishu"]["ok"] else 2
        with instance_lock(config["_dir"] / "connector.lock"):
            from .store import Store
            store = Store(state_file(config))
            if args.command == "unpair":
                if input("Type UNPAIR to remove the local owner (phone tasks will not be cancelled): ") != "UNPAIR":
                    return 1
                store.execute("DELETE FROM owners")
                print("Local pairing removed. Fixed allowed_open_ids in the config are unchanged.")
            else:
                from .runtime import run
                run(config, MikaApi(config["mika_url"], config["mika_token"]), store)
        return 0
    except (ValueError, ApiError) as exc:
        print("Check failed: " + (exc.code if isinstance(exc, ApiError) else str(exc)), file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("\nStopped. Already submitted phone tasks are unaffected.")
        return 130
    except Exception as exc:
        # SDK/network exception strings can contain authorization URLs or credentials.
        print("Connector failed (" + type(exc).__name__ + "). Check setup and connectivity; no write was retried.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
